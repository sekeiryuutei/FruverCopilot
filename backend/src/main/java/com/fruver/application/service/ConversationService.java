package com.fruver.application.service;

import com.fruver.application.port.MessagingGateway;
import com.fruver.application.port.MessagingUnavailableException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Mensajes salientes al cliente. Un mensaje de confirmacion al cliente NO es una venta:
 * solo actualiza el estado conversacional del pedido. La venta la crea unicamente SaleService.approve.
 */
@Service
public class ConversationService {

    public enum Kind { ASK, CONFIRM, INFO }

    public record MessageView(long id, String direction, String type, String content, String delivery, java.time.OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final MessagingGateway gateway;
    private final AuditService audit;
    private final SseHub hub;

    public ConversationService(JdbcClient jdbc, MessagingGateway gateway, AuditService audit, SseHub hub) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.audit = audit;
        this.hub = hub;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConversationService.class);
    private final java.util.Map<String, java.time.Instant> recentSends = new java.util.concurrent.ConcurrentHashMap<>();

    /** La llamada al proveedor ocurre SIN transaccion; el registro posterior es atomico. */
    public void send(long orderId, String text, Kind kind, String actor) {
        if (text == null || text.isBlank() || text.length() > 1000) {
            throw new ApiException(400, "VALIDATION_ERROR", "Mensaje invalido");
        }
        String cleanText = text.strip();
        String dedupKey = orderId + ":" + cleanText;
        var now = java.time.Instant.now();
        var lastSent = recentSends.get(dedupKey);
        if (lastSent != null && java.time.Duration.between(lastSent, now).toSeconds() < 8) {
            log.warn("Mensaje duplicado descartado por protección antirrebote para pedido {}: {}", orderId, cleanText);
            return;
        }
        recentSends.put(dedupKey, now);
        if (recentSends.size() > 500) {
            recentSends.entrySet().removeIf(e -> java.time.Duration.between(e.getValue(), now).toMinutes() > 5);
        }
        String phone = jdbc.sql("SELECT customer_phone FROM orders WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')")
                .param("id", orderId).query(String.class).optional()
                .orElseThrow(() -> new ApiException(409, "ORDER_NOT_ACTIVE", "El pedido no admite mensajes"));
        String delivery;
        try {
            delivery = gateway.sendText(phone, cleanText).name();
        } catch (MessagingUnavailableException e) {
            log.warn("Fallo envio por WhatsApp para pedido {}: {}", orderId, e.getMessage());
            delivery = "FAILED";
        }
        persistSend(orderId, cleanText, delivery, kind, actor);
        hub.orderChanged(orderId);
    }

    /** Registro atomico: mensaje + estado conversacional en una sola transaccion. */
    @Transactional
    void persistSend(long orderId, String text, String delivery, Kind kind, String actor) {
        // Re-verifica bajo bloqueo que el pedido siga activo.
        Integer locked = jdbc.sql("SELECT 1 FROM orders WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW') FOR UPDATE")
                .param("id", orderId).query(Integer.class).optional().orElse(null);
        if (locked == null) throw new ApiException(409, "ORDER_NOT_ACTIVE", "El pedido no admite mensajes");
        record(orderId, text, delivery);
        String cs = switch (kind) {
            case ASK -> "WAITING_FOR_CUSTOMER";
            case CONFIRM -> "CONFIRMED";
            case INFO -> "READY_FOR_REVIEW";
        };
        jdbc.sql("UPDATE orders SET conversation_status = :cs, suggested_reply = NULL WHERE id = :id")
                .param("cs", cs).param("id", orderId).update();
        audit.log(actor, switch (kind) {
            case ASK -> "CUSTOMER_ASKED";
            case CONFIRM -> "CUSTOMER_CONFIRMATION_SENT";
            case INFO -> "CUSTOMER_INFO_SENT";
        }, orderId, null, Map.of("delivery", delivery));
    }

    public List<MessageView> messages(long orderId) {
        return jdbc.sql("SELECT id, direction, message_type, content, delivery_status, created_at FROM conversation_message WHERE order_id = :o ORDER BY id")
                .param("o", orderId)
                .query((rs, n) -> new MessageView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getObject(6, java.time.OffsetDateTime.class))).list();
    }

    private void record(long orderId, String text, String delivery) {
        jdbc.sql("INSERT INTO conversation_message(order_id, direction, message_type, content, delivery_status) VALUES (:o, 'OUTBOUND', 'TEXT', :c, :d)")
                .param("o", orderId).param("c", text.strip()).param("d", delivery).update();
    }
}
