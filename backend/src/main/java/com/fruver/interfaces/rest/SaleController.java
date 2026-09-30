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
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    public SaleController(SalesQueryService salesQuery, SaleService sales, ReportService reports, CustomerService customers,
                          SseHub hub, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.salesQuery = salesQuery;
        this.sales = sales;
        this.reports = reports;
        this.customers = customers;
        this.hub = hub;
        this.jdbc = jdbc;
    }

    @GetMapping("/sales")
    public List<SalesQueryService.SaleSummary> sales(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String customerType,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String q) {
        return salesQuery.search(from, to, customerType, productId, q);
    }

    @GetMapping("/sales/{id}/items")
    public List<SalesQueryService.SaleItemView> items(@PathVariable long id) {
        return salesQuery.items(id);
    }

    /** Reimprimir / reintentar: registra auditoria y NO crea otra venta. */
    @PostMapping("/sales/{id}/reprint")
    public Map<String, Object> reprint(@PathVariable long id) {
        sales.reprint(id, Actor.name());
        jdbc.sql("SELECT order_id FROM sale WHERE id = :id").param("id", id).query(Long.class).optional().ifPresent(hub::orderChanged);
        return Map.of("status", "PRINT_PENDING");
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
