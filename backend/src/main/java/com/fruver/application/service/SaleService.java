package com.fruver.application.service;

import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.domain.enums.CustomerType;
import com.fruver.domain.enums.PriceType;
import com.fruver.infrastructure.persistence.OrderRepository;
import com.fruver.infrastructure.persistence.Rows.ItemRow;
import com.fruver.infrastructure.persistence.Rows.OrderRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "APROBAR E IMPRIMIR": valida, determina precios, recalcula y persiste la venta con snapshot,
 * y encola el trabajo de impresion. Todo en UNA transaccion sin llamadas externas.
 * La impresora nunca participa de esta transaccion: si falla despues, la venta sigue CONFIRMADA.
 */
@Service
public class SaleService {

    public record SaleResult(long saleId, long orderId, String printStatus, BigDecimal total) {}

    private final JdbcClient jdbc;
    private final OrderRepository orders;
    private final QuoteService quotes;
    private final AuditService audit;

    public SaleService(JdbcClient jdbc, OrderRepository orders, QuoteService quotes, AuditService audit) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.quotes = quotes;
        this.audit = audit;
    }

    @Transactional
    public SaleResult approve(long orderId, int expectedVersion, String user) {
        OrderRow o = orders.findForUpdate(orderId)
                .orElseThrow(() -> new ApiException(404, "ORDER_NOT_FOUND", "Pedido no encontrado"));

        // Idempotencia: un segundo clic o un segundo empleado recibe la venta ya creada, nunca una segunda venta.
        if (o.saleId() != null) {
            return jdbc.sql("SELECT id, order_id, print_status, total FROM sale WHERE id = :id").param("id", o.saleId())
                    .query((rs, n) -> new SaleResult(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getBigDecimal(4))).single();
        }
        if (!"REVIEW".equals(o.status())) {
            throw new ApiException(409, "ORDER_NOT_APPROVABLE", "El pedido no esta listo para aprobarse (estado " + o.status() + ")");
        }
        if (o.version() != expectedVersion) {
            throw new ApiException(409, "ORDER_CONFLICT", "El pedido cambio mientras lo revisabas. Recarga y vuelve a aprobar.");
        }

        List<ItemRow> items = orders.items(orderId);
        PriceType priceType = PriceType.forCustomer(CustomerType.valueOf(o.customerType()));
        Quote quote = quotes.quote(items, priceType);
        if (!quote.complete()) {
            List<String> details = new ArrayList<>();
            for (QuoteLine l : quote.lines()) if (l.problem() != null) details.add("Linea " + l.itemId() + ": " + l.problem());
            if (items.isEmpty()) details.add("El pedido no tiene lineas");
            throw new ApiException(422, "ORDER_REQUIRES_REVIEW", "El pedido requiere revision manual", details);
        }

        long saleId = jdbc.sql("""
                INSERT INTO sale(order_id, customer_id, customer_name, customer_phone, address, notes, customer_type, price_type,
                                 input_type, total, approved_by)
                VALUES (:o, :c, :n, :p, :a, :no, :ct, :pt, :it, :t, :u) RETURNING id
                """).param("o", orderId).param("c", o.customerId()).param("n", o.customerName()).param("p", o.customerPhone())
                .param("a", o.address()).param("no", o.notes()).param("ct", o.customerType()).param("pt", priceType.name())
                .param("it", o.inputType()).param("t", quote.total()).param("u", user).query(Long.class).single();

        for (int i = 0; i < items.size(); i++) {
            QuoteLine l = quote.lines().get(i);
            ItemRow it = items.get(i);
            jdbc.sql("""
                    INSERT INTO sale_item(sale_id, product_id, product_name, unit, quantity, price_type, catalog_price, applied_price,
                                          price_manually_changed, price_change_reason, subtotal)
                    VALUES (:s, :p, :n, :u, :q, :pt, :cp, :ap, :m, :r, :st)
                    """).param("s", saleId).param("p", l.productId()).param("n", l.productName()).param("u", l.unit())
                    .param("q", l.quantity()).param("pt", priceType.name()).param("cp", l.catalogPrice())
                    .param("ap", l.appliedPrice()).param("m", l.manual()).param("r", it.priceChangeReason())
                    .param("st", l.subtotal()).update();
        }

        jdbc.sql("UPDATE orders SET status = 'APPROVED', conversation_status = 'COMPLETED', approved_at = now(), version = version + 1 WHERE id = :id")
                .param("id", orderId).update();
        jdbc.sql("INSERT INTO print_job(sale_id, reprint) VALUES (:s, FALSE)").param("s", saleId).update();
        audit.log(user, "ORDER_APPROVED", orderId, saleId, Map.of("total", quote.total().toPlainString(),
                "priceType", priceType.name(), "lines", items.size()));
        return new SaleResult(saleId, orderId, "PRINT_PENDING", quote.total());
    }

    /** Reimprimir/reintentar NO crea otra venta: solo encola otro trabajo de impresion. */
    @Transactional
    public void reprint(long saleId, String user) {
        Long orderId = jdbc.sql("SELECT order_id FROM sale WHERE id = :id FOR UPDATE").param("id", saleId).query(Long.class).optional()
                .orElseThrow(() -> new ApiException(404, "SALE_NOT_FOUND", "Venta no encontrada"));
        jdbc.sql("INSERT INTO print_job(sale_id, reprint) VALUES (:s, TRUE)").param("s", saleId).update();
        jdbc.sql("UPDATE sale SET print_status = 'PRINT_PENDING', print_error = NULL WHERE id = :id").param("id", saleId).update();
        audit.log(user, "REPRINT_REQUESTED", orderId, saleId, Map.of());
    }
}
