package com.fruver.application.service;

import com.fruver.domain.enums.PriceType;
import com.fruver.domain.service.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class PriceService {

    public record HistoryEntry(String priceType, BigDecimal oldAmount, BigDecimal newAmount, String changedBy, OffsetDateTime changedAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;

    public PriceService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Cambia el precio vigente y deja historial. Las ventas ya creadas NO cambian (tienen snapshot). */
    @Transactional
    public void setPrice(long productId, PriceType type, BigDecimal amount, String user) {
        BigDecimal scaled = amount.setScale(0, Money.ROUNDING);
        if (scaled.signum() < 0 || scaled.compareTo(new BigDecimal("100000000")) > 0) {
            throw new ApiException(400, "VALIDATION_ERROR", "Precio invalido");
        }
        if (jdbc.sql("SELECT count(*) FROM product WHERE id = :id").param("id", productId).query(Integer.class).single() == 0) {
            throw new ApiException(404, "PRODUCT_NOT_FOUND", "Producto no encontrado");
        }
        Optional<BigDecimal> old = jdbc.sql("SELECT amount FROM product_price WHERE product_id = :p AND price_type = :t FOR UPDATE")
                .param("p", productId).param("t", type.name()).query(BigDecimal.class).optional();
        if (old.isPresent() && old.get().compareTo(scaled) == 0) return;

        jdbc.sql("""
                INSERT INTO product_price(product_id, price_type, amount) VALUES (:p, :t, :a)
                ON CONFLICT (product_id, price_type)
                DO UPDATE SET amount = EXCLUDED.amount, valid_from = now(), valid_to = NULL
                """).param("p", productId).param("t", type.name()).param("a", scaled).update();
        jdbc.sql("INSERT INTO product_price_history(product_id, price_type, old_amount, new_amount, changed_by) VALUES (:p, :t, :o, :n, :u)")
                .param("p", productId).param("t", type.name()).param("o", old.orElse(null)).param("n", scaled).param("u", user).update();
        audit.log(user, "PRICE_CHANGED", null, null, Map.of("productId", productId, "priceType", type.name(),
                "old", old.map(BigDecimal::toPlainString).orElse("-"), "new", scaled.toPlainString()));
    }

    public List<HistoryEntry> history(long productId) {
        return jdbc.sql("""
                SELECT price_type, old_amount, new_amount, changed_by, changed_at
                FROM product_price_history WHERE product_id = :p ORDER BY changed_at DESC, id DESC LIMIT 100
                """).param("p", productId)
                .query((rs, n) -> new HistoryEntry(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class))).list();
    }
}
