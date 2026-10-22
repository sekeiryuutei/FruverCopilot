package com.fruver.infrastructure.persistence;

import com.fruver.infrastructure.persistence.Rows.ItemRow;
import com.fruver.infrastructure.persistence.Rows.OrderRow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository {

    private static final String SELECT = """
            SELECT o.id, o.customer_id, COALESCE(o.customer_name, c.name) AS customer_name, o.customer_phone, o.address, o.notes, o.input_type,
                   o.status, o.conversation_status, o.review_status, o.ai_status, o.ai_note, o.suggested_reply,
                   o.corrected, o.version, o.received_at, o.extracted_at, o.approved_at,
                   COALESCE(c.customer_type, 'RETAIL') AS customer_type,
                   EXISTS (SELECT 1 FROM audio a WHERE a.order_id = o.id) AS has_audio,
                   EXISTS (SELECT 1 FROM media_image mi WHERE mi.order_id = o.id) AS has_image,
                   s.id AS sale_id, s.print_status AS print_status,
                   o.dispatched_at, o.dispatched_by,
                   o.delivery_person_id, o.delivery_person_name,
                   COALESCE(o.requires_verification, FALSE) AS requires_verification,
                   o.verification_reason,
                   s.target_printer, s.printed_printer
            FROM orders o
            LEFT JOIN customer c ON c.id = o.customer_id
            LEFT JOIN sale s ON s.order_id = o.id
            """;

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderRow> find(long id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id").param("id", id).query(OrderRow.class).optional();
    }

    /** Bloquea la fila del pedido (solo la tabla orders) hasta el fin de la transaccion. */
    public Optional<OrderRow> findForUpdate(long id) {
        return jdbc.sql(SELECT + " WHERE o.id = :id FOR UPDATE OF o").param("id", id).query(OrderRow.class).optional();
    }

    /** Pedidos activos + aprobados desde 'since' (para ver estado de impresion de los ultimos). */
    public List<OrderRow> listBoard(OffsetDateTime since) {
        return jdbc.sql(SELECT + """
                 WHERE o.status IN ('RECEIVED','PROCESSING','REVIEW')
                    OR (o.status = 'APPROVED' AND o.approved_at >= :since)
                 ORDER BY o.id DESC LIMIT 200
                """).param("since", since).query(OrderRow.class).list();
    }

    public List<ItemRow> items(long orderId) {
        return jdbc.sql("SELECT * FROM order_item WHERE order_id = :o ORDER BY line_no, id")
                .param("o", orderId).query(ItemRow.class).list();
    }

    public List<ItemRow> itemsFor(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return jdbc.sql("SELECT * FROM order_item WHERE order_id IN (:ids) ORDER BY order_id, line_no, id")
                .param("ids", orderIds).query(ItemRow.class).list();
    }
}
