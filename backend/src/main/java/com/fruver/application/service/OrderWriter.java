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
        String conversation = io.review() == ReviewStatus.GREEN ? "READY_FOR_APPROVAL" : "READY_FOR_REVIEW";
        String reply = io.action() == ConversationAction.ASK_CUSTOMER ? io.customerMessage() : null;
        int updated = jdbc.sql("""
                UPDATE orders SET status = 'REVIEW', review_status = :r, ai_status = 'OK', ai_note = :note,
                       suggested_reply = :reply, conversation_status = :cs, extracted_at = now(), version = version + 1
                WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                """).param("r", io.review().name()).param("note", io.note()).param("reply", reply)
                .param("cs", conversation).param("id", orderId).update();
        if (updated == 0) return false;

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
        audit.log("ia", "AI_EXTRACTED", orderId, null, Map.of("review", io.review().name(),
                "action", io.action().name(), "items", io.items().size()));
        return true;
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

    private static String clip(String s) { return s.length() <= 500 ? s : s.substring(0, 500); }
}
