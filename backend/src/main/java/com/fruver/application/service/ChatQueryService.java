package com.fruver.application.service;

import com.fruver.application.port.AudioStorageService;
import com.fruver.application.port.MessagingGateway;
import com.fruver.application.port.MessagingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
public class ChatQueryService {

    private static final Logger log = LoggerFactory.getLogger(ChatQueryService.class);

    public record ChatSummary(
            String phone,
            String name,
            Long lastOrderId,
            String lastOrderStatus,
            String lastMessage,
            String lastMessageType,
            String lastDirection,
            OffsetDateTime lastMessageAt,
            int totalMessages,
            boolean hasOrder
    ) {}

    public record ChatMessageView(
            Long id,
            Long orderId,
            String direction,
            String messageType,
            String content,
            String deliveryStatus,
            OffsetDateTime createdAt,
            Long audioId,
            Long imageId
    ) {}

    private final JdbcClient jdbc;
    private final MessagingGateway gateway;
    private final AudioStorageService storage;
    private final AuditService audit;
    private final SseHub hub;

    public ChatQueryService(JdbcClient jdbc, MessagingGateway gateway, AudioStorageService storage,
                            AuditService audit, SseHub hub) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.storage = storage;
        this.audit = audit;
        this.hub = hub;
    }

    /** Lista de conversaciones agrupadas por cliente/teléfono con su último mensaje. */
    public List<ChatSummary> listChats(String search) {
        String filter = search != null ? search.strip().toLowerCase(Locale.ROOT) : "";
        String sql = """
                WITH customer_chats AS (
                    SELECT 
                        COALESCE(c.phone, o.customer_phone) AS phone,
                        MAX(COALESCE(c.name, o.customer_name, 'Cliente')) AS name,
                        MAX(o.id) AS last_order_id,
                        MAX(cm.id) AS last_msg_id,
                        MAX(cm.created_at) AS last_message_at,
                        COUNT(cm.id) AS total_messages,
                        BOOL_OR(EXISTS (SELECT 1 FROM order_item oi WHERE oi.order_id = o.id)) AS has_order
                    FROM conversation_message cm
                    JOIN orders o ON cm.order_id = o.id
                    LEFT JOIN customer c ON o.customer_id = c.id
                    WHERE COALESCE(c.phone, o.customer_phone) IS NOT NULL
                      AND cm.created_at >= NOW() - INTERVAL '2 days'
                    GROUP BY COALESCE(c.phone, o.customer_phone)
                )
                SELECT 
                    cc.phone,
                    cc.name,
                    cc.last_order_id,
                    o.status AS last_order_status,
                    cm.content AS last_message,
                    cm.message_type AS last_message_type,
                    cm.direction AS last_direction,
                    cc.last_message_at,
                    cc.total_messages,
                    cc.has_order
                FROM customer_chats cc
                JOIN conversation_message cm ON cm.id = cc.last_msg_id
                JOIN orders o ON o.id = cc.last_order_id
                WHERE (:q = '' OR lower(cc.name) LIKE :like OR cc.phone LIKE :like)
                ORDER BY cc.last_message_at DESC
                LIMIT 150
                """;
        return jdbc.sql(sql)
                .param("q", filter)
                .param("like", "%" + filter + "%")
                .query((rs, n) -> new ChatSummary(
                        rs.getString("phone"),
                        rs.getString("name"),
                        rs.getLong("last_order_id"),
                        rs.getString("last_order_status"),
                        rs.getString("last_message"),
                        rs.getString("last_message_type"),
                        rs.getString("last_direction"),
                        rs.getObject("last_message_at", OffsetDateTime.class),
                        rs.getInt("total_messages"),
                        rs.getBoolean("has_order")
                )).list();
    }

    /** Historial cronológico de mensajes de un teléfono/cliente. */
    public List<ChatMessageView> getMessages(String phone) {
        if (phone == null || phone.isBlank()) return List.of();
        String cleanPhone = phone.replaceAll("[^0-9]", "");
        String sql = """
                SELECT 
                    cm.id,
                    cm.order_id,
                    cm.direction,
                    cm.message_type,
                    cm.content,
                    cm.delivery_status,
                    cm.created_at,
                    (SELECT a.id FROM audio a WHERE a.message_id = cm.id LIMIT 1) AS audio_id,
                    (SELECT mi.id FROM media_image mi WHERE mi.message_id = cm.id AND mi.storage_key IS NOT NULL AND TRIM(mi.storage_key) <> '' ORDER BY mi.id DESC LIMIT 1) AS image_id
                FROM conversation_message cm
                JOIN orders o ON cm.order_id = o.id
                LEFT JOIN customer c ON o.customer_id = c.id
                WHERE (COALESCE(c.phone, o.customer_phone) = :p
                   OR regexp_replace(COALESCE(c.phone, o.customer_phone), '[^0-9]', '', 'g') = :clean)
                  AND cm.created_at >= NOW() - INTERVAL '2 days'
                ORDER BY cm.id ASC
                """;
        return jdbc.sql(sql)
                .param("p", phone)
                .param("clean", cleanPhone)
                .query((rs, n) -> new ChatMessageView(
                        rs.getLong("id"),
                        rs.getLong("order_id"),
                        rs.getString("direction"),
                        rs.getString("message_type"),
                        rs.getString("content"),
                        rs.getString("delivery_status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        (Long) rs.getObject("audio_id"),
                        (Long) rs.getObject("image_id")
                )).list();
    }

    /** Envío manual de mensaje por parte del operario desde el panel de chats. */
    @Transactional
    public ChatMessageView sendManualMessage(String phone, String text, String operatorUsername) {
        if (text == null || text.isBlank() || text.length() > 2000) {
            throw new ApiException(400, "VALIDATION_ERROR", "Texto de mensaje inválido");
        }
        String cleanText = text.strip();

        // Buscar última orden asociada para registrar el mensaje
        Long orderId = jdbc.sql("""
                SELECT o.id FROM orders o
                LEFT JOIN customer c ON o.customer_id = c.id
                WHERE COALESCE(c.phone, o.customer_phone) = :p
                ORDER BY o.id DESC LIMIT 1
                """).param("p", phone).query(Long.class).optional().orElse(null);

        if (orderId == null) {
            // Si no existe ninguna orden previa, crear una interacción de chat
            Long customerId = jdbc.sql("SELECT id FROM customer WHERE phone = :p").param("p", phone).query(Long.class).optional().orElse(null);
            orderId = jdbc.sql("""
                    INSERT INTO orders (customer_id, customer_phone, customer_name, input_type, status, ai_status, conversation_status)
                    VALUES (:cid, :p, 'Cliente', 'TEXT', 'CANCELLED', 'OK', 'COMPLETED')
                    RETURNING id
                    """).param("cid", customerId).param("p", phone).query(Long.class).single();
        }

        String delivery;
        try {
            delivery = gateway.sendText(phone, cleanText).name();
        } catch (MessagingUnavailableException e) {
            log.warn("Fallo envío manual de WhatsApp para {}: {}", phone, e.getMessage());
            delivery = "FAILED";
        }

        long msgId = jdbc.sql("""
                INSERT INTO conversation_message(order_id, direction, message_type, content, delivery_status)
                VALUES (:o, 'OUTBOUND', 'TEXT', :c, :d)
                RETURNING id
                """).param("o", orderId).param("c", cleanText).param("d", delivery).query(Long.class).single();

        audit.log(operatorUsername != null ? operatorUsername : "operador", "MANUAL_CHAT_SENT", orderId, null,
                Map.of("phone", phone, "text", cleanText));

        hub.orderChanged(orderId);

        return new ChatMessageView(msgId, orderId, "OUTBOUND", "TEXT", cleanText, delivery, OffsetDateTime.now(), null, null);
    }

    /** Descarga o reproducción de audio por audio_id. */
    public ResponseEntity<ByteArrayResource> getAudio(long audioId) {
        var row = jdbc.sql("SELECT storage_key, mime_type FROM audio WHERE id = :id")
                .param("id", audioId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ApiException(404, "AUDIO_NOT_FOUND", "Audio no encontrado"));
        if (row[0] == null) throw new ApiException(404, "AUDIO_NOT_FOUND", "Audio aún no disponible");
        String raw = row[1] == null ? "audio/ogg" : row[1].split(";")[0].trim().toLowerCase(Locale.ROOT);
        String mime = switch (raw) {
            case "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/m4a",
                 "audio/wav", "audio/x-wav", "audio/webm", "audio/ogg", "audio/amr", "audio/3gpp" -> raw;
            default -> "audio/ogg";
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=\"audio-" + audioId + ".ogg\"")
                .contentType(MediaType.parseMediaType(mime))
                .body(new ByteArrayResource(storage.load(row[0])));
    }

    /** Visualización de imagen (comprobantes de pago, fotos enviadas) por media_image_id. */
    public ResponseEntity<ByteArrayResource> getImage(long imageId) {
        var row = jdbc.sql("""
                SELECT storage_key, mime_type FROM media_image
                WHERE (id = :id OR message_id = (SELECT message_id FROM media_image WHERE id = :id))
                  AND storage_key IS NOT NULL AND TRIM(storage_key) <> ''
                ORDER BY (CASE WHEN id = :id THEN 0 ELSE 1 END), id DESC
                LIMIT 1
                """)
                .param("id", imageId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ApiException(404, "IMAGE_NOT_FOUND", "Imagen no encontrada"));
        if (row[0] == null || row[0].isBlank()) throw new ApiException(404, "IMAGE_NOT_FOUND", "Imagen no disponible");
        String raw = row[1] == null ? "image/jpeg" : row[1].split(";")[0].trim().toLowerCase(Locale.ROOT);
        String mime = switch (raw) {
            case "image/jpeg", "image/jpg", "image/png", "image/webp" -> raw;
            default -> "image/jpeg";
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline; filename=\"img-" + imageId + ".jpg\"")
                .contentType(MediaType.parseMediaType(mime))
                .body(new ByteArrayResource(storage.load(row[0])));
    }
}
