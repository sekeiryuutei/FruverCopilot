package com.fruver.interfaces.rest;

import com.fruver.application.service.*;
import com.fruver.domain.enums.CustomerType;
import com.fruver.domain.service.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SaleController {

    public record TypeBody(@NotNull CustomerType customerType) {}

    private final SalesQueryService salesQuery;
    private final SaleService sales;
    private final ReportService reports;
    private final CustomerService customers;
    private final SseHub hub;
    private final TicketService tickets;
    private final com.fruver.config.AppProperties props;
    private final DeliveryZoneService deliveryZones;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final PrintService printService;
    private final com.fruver.application.service.PrinterConfigService printerConfig;

    public SaleController(SalesQueryService salesQuery, SaleService sales, ReportService reports, CustomerService customers,
                          SseHub hub, TicketService tickets, com.fruver.config.AppProperties props,
                          org.springframework.jdbc.core.simple.JdbcClient jdbc,
                          DeliveryZoneService deliveryZones,
                          PrintService printService,
                          com.fruver.application.service.PrinterConfigService printerConfig) {
        this.salesQuery = salesQuery;
        this.sales = sales;
        this.reports = reports;
        this.customers = customers;
        this.hub = hub;
        this.tickets = tickets;
        this.props = props;
        this.jdbc = jdbc;
        this.deliveryZones = deliveryZones;
        this.printService = printService;
        this.printerConfig = printerConfig;
    }

    @GetMapping("/sales")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<SalesQueryService.SaleSummary> sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String customerType,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String q) {
        return salesQuery.search(from, to, customerType, productId, q);
    }

    @GetMapping("/sales/{id}/items")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<SalesQueryService.SaleItemView> items(@PathVariable long id) {
        return salesQuery.items(id);
    }

    /** Reimprimir / reintentar: registra auditoria y NO crea otra venta. */
    @PostMapping("/sales/{id}/reprint")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> reprint(@PathVariable long id, @RequestParam(required = false) String printer) {
        sales.reprint(id, Actor.name(), printer);
        jdbc.sql("SELECT order_id FROM sale WHERE id = :id").param("id", id).query(Long.class).optional().ifPresent(hub::orderChanged);
        return Map.of("status", "PRINT_PENDING", "printer", printer != null ? printer : "AUTO");
    }

    public record PrinterConfigUpdateRequest(
            String officeHost,
            Integer officePort,
            String homeHost,
            Integer homePort,
            Boolean enabled,
            String mode,
            Integer orderGraceSeconds,
            Integer printTimeoutSeconds,
            Boolean autoPrintWholesale,
            Integer customerDebounceSeconds
    ) {
        public PrinterConfigUpdateRequest(
                String officeHost, Integer officePort, String homeHost, Integer homePort,
                Boolean enabled, String mode, Integer orderGraceSeconds, Integer printTimeoutSeconds,
                Boolean autoPrintWholesale) {
            this(officeHost, officePort, homeHost, homePort, enabled, mode, orderGraceSeconds, printTimeoutSeconds, autoPrintWholesale, null);
        }
    }

    public record TestPrinterRequest(String location) {}

    /** Estado de impresoras duales (OFICINA vs CASA) y conteo de fallos */
    @GetMapping("/printer/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public com.fruver.application.service.PrinterConfigService.PrinterConfig printerStatus() {
        return printerConfig.get();
    }

    @GetMapping("/printer/config")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public com.fruver.application.service.PrinterConfigService.PrinterConfig getPrinterConfig() {
        return printerConfig.get();
    }

    @PutMapping("/printer/config")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public com.fruver.application.service.PrinterConfigService.PrinterConfig updatePrinterConfig(@RequestBody PrinterConfigUpdateRequest req) {
        return printerConfig.update(
                req.officeHost(), req.officePort(), req.homeHost(), req.homePort(),
                req.enabled(), req.mode(),
                req.orderGraceSeconds(), req.printTimeoutSeconds(), req.autoPrintWholesale(),
                req.customerDebounceSeconds()
        );
    }

    @PostMapping("/printer/test")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> testPrinter(@RequestBody(required = false) TestPrinterRequest req) {
        String loc = (req != null && req.location() != null) ? req.location() : null;
        try {
            printerConfig.testPrint(loc);
            return Map.of("status", "OK", "message", "Impresión de prueba enviada exitosamente");
        } catch (Exception e) {
            throw new ApiException(502, "PRINTER_TEST_FAILED", e.getMessage());
        }
    }

    /** Valida y confirma un pedido pendiente o quita la marca de duda con 1 clic */
    @PostMapping("/orders/{orderId}/validate")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> validateOrder(@PathVariable long orderId) {
        var opt = jdbc.sql("SELECT status, requires_verification FROM orders WHERE id = :id").param("id", orderId)
                .query((rs, n) -> new Object[]{rs.getString(1), rs.getBoolean(2)}).optional();
        if (opt.isEmpty()) throw new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado");
        String status = (String) opt.get()[0];
        if (!"APPROVED".equals(status)) {
            sales.autoApproveWholesale(orderId, false, null, Actor.name());
        } else {
            jdbc.sql("UPDATE orders SET requires_verification = FALSE, verification_reason = NULL, version = version + 1 WHERE id = :id")
                    .param("id", orderId).update();
            jdbc.sql("UPDATE sale SET requires_verification = FALSE, verification_reason = NULL WHERE order_id = :id")
                    .param("id", orderId).update();
        }
        hub.orderChanged(orderId);
        return Map.of("status", "VALIDATED", "orderId", orderId);
    }

    /** Reintenta mandar a imprimir todos los pedidos que hayan fallado por red o papel */
    @PostMapping("/sales/reprint-all-pending")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> reprintAllPending() {
        int queued = printService.reprintAllPending();
        return Map.of("status", "enqueued", "count", queued);
    }

    /** Reimprimir buscando directamente por ID de Pedido */
    @PostMapping("/orders/{orderId}/reprint")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> reprintByOrder(@PathVariable long orderId, @RequestParam(required = false) String printer) {
        Long saleId = sales.reprintByOrderId(orderId, Actor.name(), printer);
        hub.orderChanged(orderId);
        return Map.of("status", "PRINT_PENDING", "saleId", saleId, "orderId", orderId, "printer", printer != null ? printer : "AUTO");
    }

    /** Vista previa de comanda / factura por ID de Pedido (no muta ni aprueba) */
    @GetMapping("/orders/{orderId}/ticket")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> ticketByOrder(@PathVariable long orderId) {
        Long saleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).optional().orElse(null);
        if (saleId != null) {
            return ticket(saleId);
        }
        TicketData t = tickets.buildFromOrder(orderId);
        return toTicketResponse(t, -1L);
    }

    /** Vista previa de factura (para demo sin impresora): mismo texto que iria al papel. */
    @GetMapping("/sales/{id}/ticket")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> ticket(@PathVariable long id) {
        Integer exists = jdbc.sql("SELECT 1 FROM sale WHERE id = :id").param("id", id).query(Integer.class).optional().orElse(null);
        if (exists == null) throw new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada");
        TicketData t = tickets.build(id, false);
        return toTicketResponse(t, id);
    }

    private Map<String, Object> toTicketResponse(TicketData t, Long saleId) {
        var lines = TicketFormatter.format(t, props.printing().columns());
        StringBuilder sb = new StringBuilder();
        for (var l : lines) sb.append(l.text()).append("\n");

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("saleId", saleId != null ? saleId : -1L);
        resp.put("orderId", t.orderNumber());
        resp.put("shop", t.shop() != null ? t.shop() : "FRUVER EL OASIS");
        // Modulo de precios no habilitado para impresion: ni total ni individual
        resp.put("total", BigDecimal.ZERO);
        resp.put("totalFormatted", "");
        resp.put("showPrices", false);
        resp.put("customer", t.customer() != null ? t.customer() : "");
        resp.put("phone", t.phone() != null ? t.phone() : "");
        resp.put("address", t.address() != null ? t.address() : "");
        resp.put("notes", t.notes() != null ? t.notes() : "");
        resp.put("customerType", t.customerType() != null ? t.customerType() : "RETAIL");
        resp.put("deliveryPersonName", t.deliveryPersonName() != null ? t.deliveryPersonName() : "");
        if (t.at() != null) {
            resp.put("date", t.at().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
            resp.put("day", String.format("%02d", t.at().getDayOfMonth()));
            resp.put("month", String.format("%02d", t.at().getMonthValue()));
            resp.put("year", String.valueOf(t.at().getYear()));
        } else {
            resp.put("date", "");
            resp.put("day", "");
            resp.put("month", "");
            resp.put("year", "");
        }

        List<Map<String, Object>> itemLines = new ArrayList<>();
        if (t.lines() != null) {
            for (var l : t.lines()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", l.name());
                String qStr = l.quantity() != null ? Money.qty(l.quantity()) : "";
                String uLabel = TicketFormatter.unitLabel(l.unit());
                String cant = uLabel.isBlank() ? qStr : (qStr.isBlank() ? uLabel : qStr + " " + uLabel);
                m.put("quantity", qStr);
                m.put("unit", uLabel);
                m.put("cant", cant);
                m.put("price", "");
                m.put("subtotal", "");
                itemLines.add(m);
            }
        }
        resp.put("items", itemLines);
        resp.put("text", sb.toString().stripTrailing());
        return resp;
    }

    @GetMapping("/reports/sales")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.sales(from, to);
    }

    public record CreateCustomerBody(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank String phone,
            String address,
            String addressLabel,
            CustomerType customerType
    ) {}

    public record UpdateCustomerBody(
            @jakarta.validation.constraints.NotBlank String name,
            @jakarta.validation.constraints.NotBlank String phone,
            CustomerType customerType
    ) {}

    public record AddressBody(
            @jakarta.validation.constraints.NotBlank String label,
            @jakarta.validation.constraints.NotBlank String address,
            boolean isDefault
    ) {}

    @GetMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<CustomerService.CustomerView> customers(@RequestParam(required = false) String q) {
        return customers.search(q);
    }

    @PostMapping("/customers")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public CustomerService.CustomerView createCustomer(@Valid @RequestBody CreateCustomerBody b) {
        return customers.createCustomer(b.name(), b.phone(), b.address(), b.addressLabel(),
                b.customerType() != null ? b.customerType() : CustomerType.RETAIL, Actor.name());
    }

    @PutMapping("/customers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public CustomerService.CustomerView updateCustomer(@PathVariable long id, @Valid @RequestBody UpdateCustomerBody b) {
        return customers.updateCustomer(id, b.name(), b.phone(), b.customerType(), Actor.name());
    }

    @PutMapping("/customers/{id}/type")
    @PreAuthorize("hasRole('ADMIN')")
    public void setType(@PathVariable long id, @Valid @RequestBody TypeBody b) {
        customers.setType(id, b.customerType(), Actor.name());
    }

    @GetMapping("/customers/{id}/addresses")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<CustomerService.CustomerAddressView> addresses(@PathVariable long id) {
        return customers.listAddresses(id);
    }

    @PostMapping("/customers/{id}/addresses")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public CustomerService.CustomerAddressView addAddress(@PathVariable long id, @Valid @RequestBody AddressBody b) {
        return customers.addAddress(id, b.label(), b.address(), b.isDefault(), Actor.name());
    }

    @DeleteMapping("/customers/addresses/{addressId}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public void deleteAddress(@PathVariable long addressId) {
        customers.deleteAddress(addressId, Actor.name());
    }

    // Delivery Zones
    @GetMapping("/delivery-zones")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public List<DeliveryZoneService.DeliveryZoneView> deliveryZones() {
        return deliveryZones.list();
    }

    public record DeliveryZoneBody(
            @jakarta.validation.constraints.NotBlank String name,
            boolean covered,
            java.math.BigDecimal fee,
            java.math.BigDecimal minFree,
            String estimatedTime
    ) {}

    @PostMapping("/delivery-zones")
    @PreAuthorize("hasRole('ADMIN')")
    public DeliveryZoneService.DeliveryZoneView createDeliveryZone(@Valid @RequestBody DeliveryZoneBody b) {
        return deliveryZones.create(b.name(), b.covered(), b.fee(), b.minFree(), b.estimatedTime(), Actor.name());
    }

    @PutMapping("/delivery-zones/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeliveryZoneService.DeliveryZoneView updateDeliveryZone(@PathVariable long id, @Valid @RequestBody DeliveryZoneBody b) {
        return deliveryZones.update(id, b.name(), b.covered(), b.fee(), b.minFree(), b.estimatedTime(), Actor.name());
    }

    @DeleteMapping("/delivery-zones/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteDeliveryZone(@PathVariable long id) {
        deliveryZones.delete(id, Actor.name());
    }

    // Despacho de pedido facturado
    @PostMapping("/sales/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> dispatch(@PathVariable long id) {
        sales.dispatch(id, Actor.name());
        jdbc.sql("SELECT order_id FROM sale WHERE id = :id").param("id", id).query(Long.class).optional().ifPresent(hub::orderChanged);
        return Map.of("status", "DISPATCHED");
    }

    // Reenvío manual de factura por WhatsApp
    @PostMapping("/sales/{id}/send-whatsapp")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> sendWhatsAppInvoice(@PathVariable long id) {
        sales.sendInvoiceWhatsApp(id, Actor.name());
        jdbc.sql("SELECT order_id FROM sale WHERE id = :id").param("id", id).query(Long.class).optional().ifPresent(hub::orderChanged);
        return Map.of("status", "SENT");
    }

    // Reinicio seguro de datos de prueba (pedidos y ventas) restableciendo contadores a 1
    @PostMapping("/sales/reset-test-data")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> resetTestData() {
        jdbc.sql("TRUNCATE TABLE print_job, sale_item, sale, order_item, conversation_message, audio, transcription, media_image, audit_log, orders RESTART IDENTITY CASCADE").update();
        jdbc.sql("ALTER SEQUENCE order_number_seq RESTART WITH 1").update();
        jdbc.sql("ALTER SEQUENCE sale_id_seq RESTART WITH 1").update();
        hub.orderChanged(0L);
        return Map.of("status", "ok", "message", "Datos de prueba eliminados. El próximo pedido comenzará en #1.");
    }
}

