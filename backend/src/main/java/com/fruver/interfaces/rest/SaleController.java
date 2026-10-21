package com.fruver.interfaces.rest;

import com.fruver.application.service.*;
import com.fruver.domain.enums.CustomerType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    public SaleController(SalesQueryService salesQuery, SaleService sales, ReportService reports, CustomerService customers,
                          SseHub hub, TicketService tickets, com.fruver.config.AppProperties props,
                          org.springframework.jdbc.core.simple.JdbcClient jdbc,
                          DeliveryZoneService deliveryZones,
                          PrintService printService) {
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
    public Map<String, Object> reprint(@PathVariable long id) {
        sales.reprint(id, Actor.name());
        jdbc.sql("SELECT order_id FROM sale WHERE id = :id").param("id", id).query(Long.class).optional().ifPresent(hub::orderChanged);
        return Map.of("status", "PRINT_PENDING");
    }

    /** Estado de impresoras duales (OFICINA vs CASA) y conteo de fallos */
    @GetMapping("/printer/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> printerStatus() {
        String current = props.printing().currentTargetPrinter(props.timezone());
        int failedCount = jdbc.sql("SELECT count(*) FROM sale WHERE print_status = 'PRINT_FAILED'").query(Integer.class).single();
        int pendingCount = jdbc.sql("SELECT count(*) FROM print_job WHERE status = 'PENDING'").query(Integer.class).single();
        return Map.of(
                "activePrinter", current,
                "schedule", "04:00 - 19:00 OFICINA / 19:00 - 04:00 CASA",
                "failedCount", failedCount,
                "pendingCount", pendingCount,
                "officeHost", props.printing().officeHost() + ":" + props.printing().officePort(),
                "homeHost", props.printing().homeHost() + ":" + props.printing().homePort(),
                "enabled", props.printing().enabled()
        );
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
    public Map<String, Object> reprintByOrder(@PathVariable long orderId) {
        Long saleId = sales.reprintByOrderId(orderId, Actor.name());
        hub.orderChanged(orderId);
        return Map.of("status", "PRINT_PENDING", "saleId", saleId, "orderId", orderId);
    }

    /** Vista previa de comanda / factura por ID de Pedido */
    @GetMapping("/orders/{orderId}/ticket")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> ticketByOrder(@PathVariable long orderId) {
        Long saleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).optional().orElse(null);
        if (saleId == null) {
            sales.autoApproveWholesale(orderId, false, null, Actor.name());
            saleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).single();
        }
        return ticket(saleId);
    }

    /** Vista previa de factura (para demo sin impresora): mismo texto que iria al papel. */
    @GetMapping("/sales/{id}/ticket")
    @PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
    public Map<String, Object> ticket(@PathVariable long id) {
        Integer exists = jdbc.sql("SELECT 1 FROM sale WHERE id = :id").param("id", id).query(Integer.class).optional().orElse(null);
        if (exists == null) throw new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada");
        TicketData t = tickets.build(id, false);
        var lines = TicketFormatter.format(t, props.printing().columns());
        StringBuilder sb = new StringBuilder();
        for (var l : lines) sb.append(l.text()).append("\n");
        return Map.of("saleId", id, "orderId", t.orderNumber(), "total", t.total() != null ? t.total() : 0, "text", sb.toString().stripTrailing());
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
}

