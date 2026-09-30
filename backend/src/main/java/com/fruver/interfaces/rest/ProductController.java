package com.fruver.interfaces.rest;

import com.fruver.application.service.*;
import com.fruver.domain.enums.PriceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    public record CreateBody(@NotBlank @Size(max = 120) String name, @NotBlank String unit, BigDecimal retailPrice, BigDecimal wholesalePrice) {}

    public record PatchBody(@Size(max = 120) String name, String unit, Boolean active) {}

    public record PriceBody(@NotNull BigDecimal amount) {}

    private final ProductCatalogService catalog;
    private final PriceService prices;

    public ProductController(ProductCatalogService catalog, PriceService prices) {
        this.catalog = catalog;
        this.prices = prices;
    }

    /** Lectura: OPERADOR necesita el catalogo para corregir pedidos. */
    @GetMapping
    public List<ProductCatalogService.ProductView> list(@RequestParam(required = false) String q,
                                                        @RequestParam(defaultValue = "false") boolean all) {
        return catalog.list(q, all);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody CreateBody b) {
        long id = catalog.create(b.name(), b.unit());
        if (b.retailPrice() != null) prices.setPrice(id, PriceType.RETAIL, b.retailPrice(), Actor.name());
        if (b.wholesalePrice() != null) prices.setPrice(id, PriceType.WHOLESALE, b.wholesalePrice(), Actor.name());
        return Map.of("id", id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public void patch(@PathVariable long id, @Valid @RequestBody PatchBody b) {
        catalog.update(id, b.name(), b.unit(), b.active());
    }

    @PutMapping("/{id}/prices/{type}")
    @PreAuthorize("hasRole('ADMIN')")
    public void setPrice(@PathVariable long id, @PathVariable PriceType type, @Valid @RequestBody PriceBody b) {
        prices.setPrice(id, type, b.amount(), Actor.name());
    }

    @GetMapping("/{id}/price-history")
    @PreAuthorize("hasRole('ADMIN')")
    public List<PriceService.HistoryEntry> history(@PathVariable long id) {
        return prices.history(id);
    }
}
