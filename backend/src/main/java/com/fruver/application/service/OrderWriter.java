package com.fruver.application.service;

import com.fruver.application.dto.InterpretedOrder;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.ReviewStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Escrituras cortas y transaccionales del resultado de IA. Nunca se llama al LLM desde aqui. */
@Service
public class OrderWriter {

    private final JdbcClient jdbc;
    private final AuditService audit;

    public OrderWriter(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** @return false si el pedido ya no esta activo (p. ej. cancelado mientras la IA pensaba). */
    @Transactional
    public boolean applyExtraction(long orderId, InterpretedOrder io) {
        // Bloqueo pesimista para serializar con la edicion humana (OrderEditService usa FOR UPDATE + version).
        // Si el empleado ya corrigio (corrected=TRUE), la IA NO pisa sus lineas: solo deja nota de IA.
        var state = jdbc.sql("SELECT version, corrected, status FROM orders WHERE id = :id FOR UPDATE")
                .param("id", orderId).query((rs, n) -> new long[]{rs.getLong(1), rs.getBoolean(2) ? 1 : 0})
                .optional();
        if (state.isEmpty()) return false;
        boolean humanCorrected = state.get()[1] == 1;
        if (humanCorrected) {
            jdbc.sql("""
                    UPDATE orders SET ai_status = 'OK', ai_note = :note, extracted_at = now(), version = version + 1
                    WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                    """).param("note", io.note()).param("id", orderId).update();
            audit.log("ia", "AI_EXTRACTED_SKIPPED_HUMAN_EDIT", orderId, null, Map.of("review", io.review().name()));
            return true;
        }
        String conversation = io.review() == ReviewStatus.GREEN ? "READY_FOR_APPROVAL" : "READY_FOR_REVIEW";
        String reply = io.action() == ConversationAction.ASK_CUSTOMER ? io.customerMessage() : null;
        int updated = jdbc.sql("""
                UPDATE orders SET status = 'REVIEW', review_status = :r, ai_status = 'OK', ai_note = :note,
                       suggested_reply = :reply, conversation_status = :cs, extracted_at = now(), version = version + 1
                WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                """).param("r", io.review().name()).param("note", io.note()).param("reply", reply)
                .param("cs", conversation).param("id", orderId).update();
        if (updated == 0) return false;

        if (io.address() != null && !io.address().isBlank()) {
            jdbc.sql("""
                    UPDATE orders SET address = :addr
                    WHERE id = :id
                    """).param("addr", io.address()).param("id", orderId).update();

            boolean isPickup = io.address().toLowerCase().contains("recoge")
                    || io.address().toLowerCase().contains("tienda")
                    || io.address().toLowerCase().contains("punto");

            if (!isPickup) {
                jdbc.sql("""
                        UPDATE customer SET address = COALESCE(customer.address, :addr)
                        FROM orders o WHERE o.id = :id AND customer.id = o.customer_id
                        """).param("addr", io.address()).param("id", orderId).update();
                jdbc.sql("""
                        INSERT INTO customer_address(customer_id, label, address, is_default)
                        SELECT o.customer_id, 'Casa', :addr, TRUE
                        FROM orders o
                        WHERE o.id = :id AND o.customer_id IS NOT NULL
                          AND NOT EXISTS (
                              SELECT 1 FROM customer_address ca
                              WHERE ca.customer_id = o.customer_id AND lower(trim(ca.address)) = lower(trim(:addr))
                          )
                        """).param("addr", io.address()).param("id", orderId).update();
            }
        }

        if (io.deliveryTime() != null && !io.deliveryTime().isBlank()) {
            String note = "Horario: " + io.deliveryTime().strip();
            jdbc.sql("""
                    UPDATE orders SET notes = CASE
                        WHEN notes IS NULL OR notes = '' THEN :n
                        WHEN notes NOT ILIKE '%' || :n || '%' THEN notes || ' | ' || :n
                        ELSE notes END
                    WHERE id = :id
                    """).param("n", note).param("id", orderId).update();
        }

        if (io.customerName() != null && !io.customerName().isBlank()) {
            updateCustomerName(orderId, io.customerName().strip());
        }

        if (io.paymentInfo() != null && !io.paymentInfo().isBlank()) {
            updatePaymentNote(orderId, io.paymentInfo().strip());
        }

        if (!io.items().isEmpty()) {
            jdbc.sql("DELETE FROM order_item WHERE order_id = :o").param("o", orderId).update();
            int line = 0;
            for (InterpretedOrder.Item i : io.items()) {
                jdbc.sql("""
                        INSERT INTO order_item(order_id, line_no, mentioned, ai_product_name, ai_quantity, ai_unit, ai_ambiguous,
                                               observation, product_id, quantity, unit)
                        VALUES (:o, :ln, :m, :apn, :aq, :au, :aa, :obs, :pid, :q, :u)
                        """).param("o", orderId).param("ln", line++).param("m", i.mentioned()).param("apn", i.aiProductName())
                        .param("aq", i.aiQuantity()).param("au", i.aiUnit()).param("aa", i.aiAmbiguous())
                        .param("obs", i.observation()).param("pid", i.productId()).param("q", i.quantity())
                        .param("u", i.unit() == null ? null : i.unit().name()).update();
            }
        }
        audit.log("ia", "AI_EXTRACTED", orderId, null, Map.of("review", io.review().name(),
                "action", io.action().name(), "items", io.items().size()));
        return true;
    }

    @Transactional
    public void markConfirmed(long orderId) {
        jdbc.sql("""
                UPDATE orders SET conversation_status = 'CONFIRMED', extracted_at = now(), version = version + 1
                WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                """).param("id", orderId).update();
    }

    @Transactional
    public void updateCustomerName(long orderId, String name) {
        if (name == null || name.isBlank()) return;
        String clean = name.strip();
        jdbc.sql("UPDATE orders SET customer_name = :n WHERE id = :id")
                .param("n", clean).param("id", orderId).update();
        jdbc.sql("UPDATE customer SET name = :n WHERE id = (SELECT customer_id FROM orders WHERE id = :id)")
                .param("n", clean).param("id", orderId).update();
    }

    @Transactional
    public void updatePaymentNote(long orderId, String paymentNote) {
        if (paymentNote == null || paymentNote.isBlank()) return;
        String clean = paymentNote.strip();
        String currentNotes = jdbc.sql("SELECT notes FROM orders WHERE id = :id").param("id", orderId).query(String.class).optional().orElse(null);
        String updated;
        if (currentNotes == null || currentNotes.isBlank()) {
            updated = clean;
        } else {
            java.util.List<String> parts = new java.util.ArrayList<>();
            for (String p : currentNotes.split("\\|")) {
                String s = p.trim();
                if (!s.toLowerCase(java.util.Locale.ROOT).startsWith("pago:")) {
                    parts.add(s);
                }
            }
            parts.add(clean);
            updated = String.join(" | ", parts);
        }
        jdbc.sql("UPDATE orders SET notes = :n WHERE id = :id").param("n", updated).param("id", orderId).update();
    }

    @Transactional
    public void markInquiryHandled(long orderId, String note) {
        jdbc.sql("""
                UPDATE orders SET status = 'REVIEW', review_status = 'YELLOW', ai_status = 'OK', ai_note = :note,
                       conversation_status = 'WAITING_FOR_CUSTOMER', extracted_at = now(), version = version + 1
                WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                """).param("note", note).param("id", orderId).update();
        audit.log("ia", "AI_INQUIRY_ANSWERED", orderId, null, Map.of("note", note));
    }

    @Transactional
    public void markWaitingForOperator(long orderId, String note, String suggestedReply) {
        jdbc.sql("""
                UPDATE orders SET status = 'REVIEW', review_status = 'YELLOW', ai_status = 'OK', ai_note = :note,
                       suggested_reply = :reply, conversation_status = 'READY_FOR_REVIEW', extracted_at = now(), version = version + 1
                WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                """).param("note", note).param("reply", suggestedReply).param("id", orderId).update();
        audit.log("ia", "AI_WAITING_FOR_OPERATOR", orderId, null, Map.of("note", note));
    }

    @Transactional
    public void markFailed(long orderId, String aiStatus, String note) {
        jdbc.sql("""
                UPDATE orders SET status = 'REVIEW', review_status = 'RED', ai_status = :s, ai_note = :n,
                       conversation_status = 'READY_FOR_REVIEW', version = version + 1
                WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                """).param("s", aiStatus).param("n", note).param("id", orderId).update();
        audit.log("ia", "AI_" + aiStatus, orderId, null, Map.of("note", note));
    }

    @Transactional
    public void attachAudio(long messageId, String storageKey) {
        jdbc.sql("UPDATE audio SET storage_key = :k WHERE message_id = :m").param("k", storageKey).param("m", messageId).update();
    }

    @Transactional
    public void saveTranscription(long messageId, String text, String model) {
        jdbc.sql("UPDATE conversation_message SET content = :t WHERE id = :m").param("t", text).param("m", messageId).update();
        jdbc.sql("INSERT INTO transcription(audio_id, message_id, text, model) SELECT a.id, :m, :t, :mo FROM audio a WHERE a.message_id = :m")
                .param("m", messageId).param("t", text).param("mo", model).update();
    }

    public List<String> recentTurns(long orderId, long excludeMessageId) {
        List<String> turns = jdbc.sql("""
                SELECT direction, content FROM conversation_message
                WHERE order_id = :o AND id <> :m AND content IS NOT NULL ORDER BY id DESC LIMIT 6
                """).param("o", orderId).param("m", excludeMessageId)
                .query((rs, n) -> ("INBOUND".equals(rs.getString(1)) ? "CLIENTE: " : "TIENDA: ") + clip(rs.getString(2))).list();
        return turns.reversed();
    }

    public List<String> currentItems(long orderId) {
        return jdbc.sql("SELECT COALESCE(ai_product_name, mentioned), quantity, unit FROM order_item WHERE order_id = :o ORDER BY line_no")
                .param("o", orderId)
                .query((rs, n) -> rs.getString(1) + " " + (rs.getBigDecimal(2) == null ? "?" : rs.getBigDecimal(2).stripTrailingZeros().toPlainString())
                        + " " + (rs.getString(3) == null ? "?" : rs.getString(3))).list();
    }

    public String customerFavorites(long orderId) {
        Long customerId = jdbc.sql("SELECT customer_id FROM orders WHERE id = :id")
                .param("id", orderId)
                .query(Long.class).optional().orElse(null);
        if (customerId == null) return null;
        List<String> favs = jdbc.sql("""
                SELECT product_name FROM (
                    SELECT si.product_name, count(*) as cnt FROM sale_item si
                    JOIN sale s ON si.sale_id = s.id
                    WHERE s.customer_id = :cid
                    GROUP BY si.product_name
                    UNION ALL
                    SELECT p.name, count(*) as cnt FROM order_item oi
                    JOIN orders o ON oi.order_id = o.id
                    JOIN product p ON oi.product_id = p.id
                    WHERE o.customer_id = :cid AND oi.product_id IS NOT NULL
                    GROUP BY p.name
                ) h
                GROUP BY product_name
                ORDER BY sum(cnt) DESC
                LIMIT 6
                """).param("cid", customerId)
                .query(String.class).list();
        if (favs.isEmpty()) return null;
        return "Productos frecuentes que suele comprar este cliente: " + String.join(", ", favs) + ".";
    }

    public boolean hasPriorMessages(long orderId) {
        Long count = jdbc.sql("SELECT count(*) FROM conversation_message WHERE order_id = :id")
                .param("id", orderId).query(Long.class).optional().orElse(0L);
        return count > 1;
    }

    private static String clip(String s) { return s.length() <= 500 ? s : s.substring(0, 500); }
}
