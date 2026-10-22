package com.fruver.application.service;

import com.fruver.application.dto.IntakeResult;
import com.fruver.domain.enums.OrderInputType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro del mensaje entrante: transaccion CORTA, sin llamadas externas.
 * Si el cliente tiene un pedido esperando su respuesta, el mensaje se une a ese pedido; si no, crea uno nuevo.
 */
@Service
public class OrderIntakeService {

    private record CustomerRef(long id, String type) {}

    private final JdbcClient jdbc;

    public OrderIntakeService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isDuplicate(String externalId) {
        if (externalId == null) return false;
        return jdbc.sql("SELECT count(*) FROM conversation_message WHERE external_message_id = :e")
                .param("e", externalId).query(Integer.class).single() > 0;
    }

    @Transactional
    public IntakeResult record(String phone, String name, String externalId, OrderInputType type, String text,
                               String mediaId, String storageKey, String mime) {
        CustomerRef customer = jdbc.sql("""
                INSERT INTO customer(name, phone) VALUES (:n, :p)
                ON CONFLICT (phone) DO UPDATE SET name = COALESCE(customer.name, EXCLUDED.name)
                RETURNING id, customer_type
                """).param("n", name).param("p", phone)
                .query((rs, i) -> new CustomerRef(rs.getLong(1), rs.getString(2))).single();

        var activeOrder = jdbc.sql("""
                SELECT id FROM orders WHERE customer_id = :c
                AND status IN ('RECEIVED','PROCESSING','REVIEW')
                AND received_at >= NOW() - INTERVAL '12 hours'
                ORDER BY id DESC LIMIT 1 FOR UPDATE
                """).param("c", customer.id()).query(Long.class).optional();

        long orderId;
        boolean reply = activeOrder.isPresent();
        if (reply) {
            orderId = activeOrder.get();
            var prevSaleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :id").param("id", orderId).query(Long.class).optional();
            if (prevSaleId.isPresent() && prevSaleId.get() != null) {
                long sId = prevSaleId.get();
                jdbc.sql("DELETE FROM print_job WHERE sale_id = :s").param("s", sId).update();
                jdbc.sql("DELETE FROM sale_item WHERE sale_id = :s").param("s", sId).update();
                jdbc.sql("DELETE FROM sale WHERE id = :s").param("s", sId).update();
            }
            jdbc.sql("UPDATE orders SET status = 'PROCESSING', ai_status = 'PENDING', conversation_status = 'READY_FOR_REVIEW' WHERE id = :id")
                    .param("id", orderId).update();
        } else {
            orderId = jdbc.sql("""
                    INSERT INTO orders(customer_id, customer_name, customer_phone, address, input_type, status, ai_status)
                    SELECT c.id, c.name, c.phone, c.address, :t, 'PROCESSING', 'PENDING' FROM customer c WHERE c.id = :c
                    RETURNING id
                    """).param("t", type.name()).param("c", customer.id()).query(Long.class).single();
        }

        boolean audio = type == OrderInputType.AUDIO;
        boolean image = type == OrderInputType.IMAGE;
        String msgType = audio ? "AUDIO" : (image ? "IMAGE" : "TEXT");
        long messageId = jdbc.sql("""
                INSERT INTO conversation_message(order_id, direction, message_type, content, external_message_id)
                VALUES (:o, 'INBOUND', :mt, :c, :e) RETURNING id
                """).param("o", orderId).param("mt", msgType).param("c", text)
                .param("e", externalId).query(Long.class).single();

        if (audio) {
            jdbc.sql("INSERT INTO audio(order_id, message_id, media_ref, storage_key, mime_type) VALUES (:o, :m, :r, :k, :t)")
                    .param("o", orderId).param("m", messageId).param("r", mediaId).param("k", storageKey).param("t", mime).update();
        } else if (image) {
            jdbc.sql("INSERT INTO media_image(order_id, message_id, media_ref, storage_key, mime_type, caption) VALUES (:o, :m, :r, :k, :t, :c)")
                    .param("o", orderId).param("m", messageId).param("r", mediaId).param("k", storageKey).param("t", mime).param("c", text).update();
        }
        return new IntakeResult(orderId, messageId, reply, audio, image, text, mediaId, storageKey, mime);
    }
}
