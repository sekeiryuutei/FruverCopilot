package com.fruver.application.service;

import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.domain.enums.PriceType;
import com.fruver.domain.service.Money;
import com.fruver.infrastructure.persistence.Rows.ItemRow;
import com.fruver.infrastructure.persistence.Rows.ProductRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unica fuente de verdad para precios y totales. El precio lo determina el backend segun el tipo de cliente:
 * cliente -> tipo -> PriceType -> precio vigente. Nunca el LLM ni el frontend.
 */
@Service
public class QuoteService {

    /** Productos y precios vigentes, cargados en bloque para evitar consultas N+1 en la pantalla operativa. */
    public record PriceBook(Map<Long, ProductRow> products, Map<PriceType, Map<Long, BigDecimal>> prices) {}

    private final JdbcClient jdbc;

    public QuoteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public PriceBook priceBook(Collection<Long> productIds) {
        Map<Long, ProductRow> products = new HashMap<>();
        Map<PriceType, Map<Long, BigDecimal>> prices = new EnumMap<>(PriceType.class);
        for (PriceType t : PriceType.values()) prices.put(t, new HashMap<>());
        Set<Long> ids = productIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return new PriceBook(products, prices);
        jdbc.sql("SELECT id, name, unit, active, image_url FROM product WHERE id IN (:ids)").param("ids", ids)
                .query(ProductRow.class).list().forEach(p -> products.put(p.id(), p));
        jdbc.sql("""
                SELECT product_id, price_type, amount FROM product_price
                WHERE product_id IN (:ids) AND valid_from <= now() AND (valid_to IS NULL OR valid_to > now())
                """).param("ids", ids).query((rs, n) -> {
                    try {
                        prices.get(PriceType.valueOf(rs.getString(2))).put(rs.getLong(1), rs.getBigDecimal(3));
                    } catch (IllegalArgumentException ignored) { /* tipo de precio futuro aun no soportado */ }
                    return 0;
                }).list();
        return new PriceBook(products, prices);
    }

    public Quote quote(List<ItemRow> items, PriceType type) {
        return quote(items, type, priceBook(items.stream().map(ItemRow::productId).toList()));
    }

    public Quote quote(List<ItemRow> items, PriceType type, PriceBook book) {
        List<QuoteLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = !items.isEmpty();
        for (ItemRow it : items) {
            ProductRow p = it.productId() == null ? null : book.products().get(it.productId());
            BigDecimal catalog = p == null ? null : book.prices().get(type).get(p.id());
            BigDecimal applied = it.manualPrice() != null ? it.manualPrice() : catalog;
            String problem = problemOf(it, p, applied);
            // Sin conversion automatica KG<->LB: una unidad distinta a la del producto
            // siempre requiere revision humana (UNIT_MISMATCH). Evita facturar
            // "2 KG" como "4 LB" sin aviso.
            BigDecimal effectiveQty = it.quantity();
            String displayUnit = it.unit();
            BigDecimal subtotal = problem == null ? Money.line(effectiveQty, applied) : null;
            boolean manual = it.manualPrice() != null && (catalog == null || it.manualPrice().compareTo(catalog) != 0);
            if (problem != null) complete = false; else total = total.add(subtotal);
            lines.add(new QuoteLine(it.id(), p == null ? null : p.id(), p == null ? null : p.name(), effectiveQty,
                    displayUnit, catalog, applied, manual, subtotal, problem));
        }
        return new Quote(lines, total, complete, type.name());
    }

    private static String problemOf(ItemRow it, ProductRow p, BigDecimal applied) {
        if (p == null) return "NO_PRODUCT";
        if (!p.active()) return "INACTIVE_PRODUCT";
        if (it.quantity() == null || it.quantity().signum() <= 0) return "NO_QUANTITY";
        if (it.unit() == null) return "NO_UNIT";
        if (!it.unit().equals(p.unit())) return "UNIT_MISMATCH";
        if (applied == null) return "NO_PRICE";
        return null;
    }
}
