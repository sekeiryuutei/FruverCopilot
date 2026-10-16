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
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public SaleController(SalesQueryService salesQuery, SaleService sales, ReportService reports, CustomerService customers,
                          SseHub hub, TicketService tickets, com.fruver.config.AppProperties props,
                          org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.salesQuery = salesQuery;
        this.sales = sales;
        this.reports = reports;
        this.customers = customers;
        this.hub = hub;
        this.tickets = tickets;
        this.props = props;
        this.jdbc = jdbc;
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
        return Map.of("saleId", id, "orderId", t.orderNumber(), "total", t.total(), "text", sb.toString().stripTrailing());
    }

    @GetMapping("/reports/sales")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.sales(from, to);
    }

    @GetMapping("/customers")
    @PreAuthorize("hasRole('ADMIN')")
    public List<CustomerService.CustomerView> customers(@RequestParam(required = false) String q) {
        return customers.search(q);
    }

    @PutMapping("/customers/{id}/type")
    @PreAuthorize("hasRole('ADMIN')")
    public void setType(@PathVariable long id, @Valid @RequestBody TypeBody b) {
        customers.setType(id, b.customerType(), Actor.name());
    }
}
