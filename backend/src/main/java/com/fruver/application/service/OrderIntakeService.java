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
    private final com.fruver.config.AppProperties props;

    public OrderIntakeService(JdbcClient jdbc, com.fruver.config.AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public boolean isDuplicate(String externalId) {
        if (externalId == null) return false;
        return jdbc.sql("SELECT count(*) FROM conversation_message WHERE external_message_id = :e")
                .param("e", externalId).query(Integer.class).single() > 0;
    }

    public IntakeResult record(String phone, String name, String externalId, OrderInputType type, String text,
                               String mediaId, String storageKey, String mime) {
        return record(phone, name, externalId, type, text, mediaId, storageKey, mime, "BAILEYS_QR");
    }

    @Transactional
    public IntakeResult record(String phone, String name, String externalId, OrderInputType type, String text,
                               String mediaId, String storageKey, String mime, String channel) {
        String normPhone = Phones.normalize(phone);
        String initialSource = (name != null && !name.isBlank()) ? "WHATSAPP_PROFILE" : "UNVERIFIED";
        CustomerRef customer = jdbc.sql("""
                INSERT INTO customer(name, phone, name_source) VALUES (:n, :p, :src)
                ON CONFLICT (phone) DO UPDATE SET
                    name = CASE
                        WHEN customer.name_source IN ('MANUAL','IMPORTED_CONTACT') THEN customer.name
                        WHEN customer.name IS NOT NULL AND customer.name <> '' AND customer.name <> 'Cliente' THEN customer.name
                        ELSE COALESCE(EXCLUDED.name, customer.name)
                    END,
                    name_source = CASE
                        WHEN customer.name_source IN ('MANUAL','IMPORTED_CONTACT') THEN customer.name_source
                        WHEN EXCLUDED.name IS NOT NULL AND EXCLUDED.name <> '' THEN 'WHATSAPP_PROFILE'
                        ELSE customer.name_source
                    END
                RETURNING id, customer_type
                """).param("n", name).param("p", normPhone).param("src", initialSource)
                .query((rs, i) -> new CustomerRef(rs.getLong(1), rs.getString(2))).single();

        boolean wholesaleMode = props.printing().autoPrintWholesale();
        var activeOrder = jdbc.sql("""
                SELECT o.id FROM orders o
                WHERE o.customer_id = :c
                AND (
                    o.status IN ('RECEIVED','PROCESSING','REVIEW')
                    OR (
                        :wholesale = TRUE
                        AND o.status = 'APPROVED'
                        AND NOT EXISTS (
                            SELECT 1 FROM sale s
                            WHERE s.order_id = o.id AND s.print_status = 'PRINTED'
                        )
                    )
                )
                AND o.received_at >= NOW() - INTERVAL '12 hours'
                ORDER BY o.id DESC LIMIT 1 FOR UPDATE
                """).param("c", customer.id()).param("wholesale", wholesaleMode).query(Long.class).optional();

        long orderId;
        boolean reply = activeOrder.isPresent();
        String chan = (channel != null && !channel.isBlank()) ? channel : "BAILEYS_QR";
        if (reply) {
            orderId = activeOrder.get();
            var prevSaleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :id").param("id", orderId).query(Long.class).optional();
            if (prevSaleId.isPresent() && prevSaleId.get() != null) {
                long sId = prevSaleId.get();
                jdbc.sql("DELETE FROM print_job WHERE sale_id = :s").param("s", sId).update();
                jdbc.sql("DELETE FROM sale_item WHERE sale_id = :s").param("s", sId).update();
                jdbc.sql("DELETE FROM sale WHERE id = :s").param("s", sId).update();
            }
            jdbc.sql("UPDATE orders SET status = 'PROCESSING', ai_status = 'PENDING', conversation_status = 'READY_FOR_REVIEW', channel = :ch WHERE id = :id")
                    .param("ch", chan).param("id", orderId).update();
        } else {
            orderId = jdbc.sql("""
                    INSERT INTO orders(customer_id, customer_name, customer_phone, address, input_type, status, ai_status, channel)
                    SELECT c.id, c.name, c.phone, c.address, :t, 'PROCESSING', 'PENDING', :ch FROM customer c WHERE c.id = :c
                    RETURNING id
                    """).param("t", type.name()).param("ch", chan).param("c", customer.id()).query(Long.class).single();
        }

        boolean audio = type == OrderInputType.AUDIO;
        boolean image = type == OrderInputType.IMAGE;
        String msgType = audio ? "AUDIO" : (image ? "IMAGE" : "TEXT");
        long messageId = jdbc.sql("""
                INSERT INTO conversation_message(order_id, direction, message_type, content, external_message_id, channel)
                VALUES (:o, 'INBOUND', :mt, :c, :e, :ch) RETURNING id
                """).param("o", orderId).param("mt", msgType).param("c", text)
                .param("e", externalId).param("ch", chan).query(Long.class).single();

        if (audio) {
            jdbc.sql("INSERT INTO audio(order_id, message_id, media_ref, storage_key, mime_type) VALUES (:o, :m, :r, :k, :t)")
                    .param("o", orderId).param("m", messageId).param("r", mediaId).param("k", storageKey).param("t", mime).update();
        } else if (image) {
            jdbc.sql("INSERT INTO media_image(order_id, message_id, media_ref, storage_key, mime_type, caption) VALUES (:o, :m, :r, :k, :t, :c)")
                    .param("o", orderId).param("m", messageId).param("r", mediaId).param("k", storageKey).param("t", mime).param("c", text).update();
        }
        return new IntakeResult(orderId, messageId, reply, audio, image, text, mediaId, storageKey, mime);
    }

    /**
     * Registra un mensaje saliente enviado desde el dispositivo móvil (WhatsApp celular físico).
     * No crea pedidos activos, no modifica estados de facturación y no dispara extracción de IA.
     */
    @Transactional
    public Long recordOutbound(String phone, String externalId, String text, String messageType,
                               String mediaId, String storageKey, String mime, String channel) {
        if (externalId != null && isDuplicate(externalId)) {
            return null;
        }
        String normPhone = Phones.normalize(phone);
        String chan = (channel != null && !channel.isBlank()) ? channel : "BAILEYS_QR";
        String msgType = (messageType != null && !messageType.isBlank()) ? messageType.toUpperCase() : "TEXT";

        // 1. Obtener o registrar cliente
        Long customerId = jdbc.sql("SELECT id FROM customer WHERE phone = :p")
                .param("p", normPhone).query(Long.class).optional().orElse(null);
        if (customerId == null) {
            customerId = jdbc.sql("INSERT INTO customer(name, phone, name_source) VALUES ('Cliente', :p, 'UNVERIFIED') RETURNING id")
                    .param("p", normPhone).query(Long.class).single();
        }

        // 2. Buscar última orden para asociar la conversación sin alterar su estado
        Long orderId = jdbc.sql("""
                SELECT o.id FROM orders o
                WHERE o.customer_id = :cid OR o.customer_phone = :p
                ORDER BY o.id DESC LIMIT 1
                """).param("cid", customerId).param("p", normPhone).query(Long.class).optional().orElse(null);

        // Si el cliente no tiene ninguna orden previa, crear un contenedor inerte (CANCELLED / OK)
        // para satisfacer la foreign key NOT NULL de conversation_message sin activar IA ni facturación
        if (orderId == null) {
            orderId = jdbc.sql("""
                    INSERT INTO orders (customer_id, customer_phone, customer_name, input_type, status, ai_status, conversation_status, channel)
                    VALUES (:cid, :p, 'Cliente', 'TEXT', 'CANCELLED', 'OK', 'COMPLETED', :ch)
                    RETURNING id
                    """).param("cid", customerId).param("p", normPhone).param("ch", chan).query(Long.class).single();
        }

        // 3. Insertar el mensaje con dirección 'OUTBOUND' y delivery_status 'SENT'
        long messageId = jdbc.sql("""
                INSERT INTO conversation_message(order_id, direction, message_type, content, external_message_id, delivery_status, channel)
                VALUES (:o, 'OUTBOUND', :mt, :c, :e, 'SENT', :ch)
                RETURNING id
                """).param("o", orderId).param("mt", msgType).param("c", text)
                .param("e", externalId).param("ch", chan).query(Long.class).single();

        if ("AUDIO".equalsIgnoreCase(msgType) && mediaId != null) {
            jdbc.sql("INSERT INTO audio(order_id, message_id, media_ref, storage_key, mime_type) VALUES (:o, :m, :r, :k, :t)")
                    .param("o", orderId).param("m", messageId).param("r", mediaId).param("k", storageKey).param("t", mime).update();
        } else if ("IMAGE".equalsIgnoreCase(msgType) && mediaId != null) {
            jdbc.sql("INSERT INTO media_image(order_id, message_id, media_ref, storage_key, mime_type, caption) VALUES (:o, :m, :r, :k, :t, :c)")
                    .param("o", orderId).param("m", messageId).param("r", mediaId).param("k", storageKey).param("t", mime).param("c", text).update();
        }

        return orderId;
    }
}
