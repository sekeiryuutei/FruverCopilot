package com.fruver.application.service;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.InterpretedOrder;
import com.fruver.application.dto.InterpretedOrder.Item;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.ProductLookup;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.domain.service.ItemAssessment;
import com.fruver.domain.service.ReviewEvaluator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Trata la respuesta del LLM como UNTRUSTED INPUT: valida estructura, cantidades, unidades y catalogo,
 * calcula el semaforo con reglas deterministicas y decide la accion conversacional.
 * JSON valido no es pedido correcto.
 */
@Component
public class ExtractionInterpreter {

    static final int MAX_ITEMS = 50;
    static final BigDecimal MAX_QTY = new BigDecimal("100000");

    private final ProductLookup lookup;
    private final ReviewEvaluator evaluator = new ReviewEvaluator();

    public ExtractionInterpreter(ProductLookup lookup) {
        this.lookup = lookup;
    }

    public InterpretedOrder interpret(AIOrderResponse ai) {
        List<Item> items = new ArrayList<>();
        List<ItemAssessment> assessments = new ArrayList<>();
        List<ItemSugeridoDTO> raw = ai == null || ai.items() == null ? List.of() : ai.items();
        boolean truncated = raw.size() > MAX_ITEMS;

        for (ItemSugeridoDTO d : raw) {
            if (d == null || items.size() >= MAX_ITEMS) continue;
            // Si el item viene vacío (sin producto mencionado ni normalizado), no crearlo como item de pedido
            if ((d.productoMencionado() == null || d.productoMencionado().isBlank())
                    && (d.productoNormalizado() == null || d.productoNormalizado().isBlank())) {
                continue;
            }
            UnitOfMeasure unit = UnitOfMeasure.parse(d.unidadMedida()).orElse(null);
            BigDecimal qty = sanitizeQuantity(d.cantidad());
            ProductLookup.Resolution res = lookup.resolve(d);
            String mentioned = clip(d.productoMencionado(), 200);
            if (mentioned.isBlank()) mentioned = clip(d.productoNormalizado(), 200);
            if (mentioned.isBlank()) mentioned = "(sin texto)";

            items.add(new Item(mentioned, blankToNull(clip(d.productoNormalizado(), 200)), storableAiQuantity(d.cantidad()),
                    d.unidadMedida() == null ? null : clip(d.unidadMedida(), 20), d.ambiguo(),
                    blankToNull(clip(d.observacion(), 300)),
                    res.matches() == 1 ? res.productId() : null, qty, unit));
            assessments.add(new ItemAssessment(res.matches() == 1 && res.productId() != null,
                    res.matches(), qty, unit, d.ambiguo()));
        }

        boolean contradiction = ai != null && ((ai.clarificationReason() != null && ai.clarificationReason().toLowerCase().contains("contradic"))
                || (raw != null && raw.stream().anyMatch(d -> d != null && d.observacion() != null && d.observacion().toLowerCase().contains("contradic"))));
        ReviewStatus review = evaluator.evaluate(assessments, contradiction);
        // Pedido grande truncado a MAX_ITEMS: nunca GREEN silencioso, siempre a revision.
        if (truncated && review == ReviewStatus.GREEN) review = ReviewStatus.YELLOW;
        String reason = blankToNull(clip(ai == null ? null : ai.clarificationReason(), 500));
        ConversationAction requested = ai == null || ai.action() == null ? ConversationAction.NONE : ai.action();
        String aiMessage = sanitizeReply(ai == null ? null : ai.customerMessage());
        String address = ai == null ? null : blankToNull(clip(ai.direccion(), 300));
        String time = ai == null ? null : blankToNull(clip(ai.horarioEntrega(), 100));
        String customerName = ai == null ? null : blankToNull(clip(ai.nombreCliente(), 120));
        String paymentInfo = ai == null ? null : blankToNull(clip(ai.metodoPago(), 300));

        ConversationAction action;
        String message = null;
        if (!items.isEmpty()) {
            if (requested == ConversationAction.WAIT_FOR_OPERATOR || requested == ConversationAction.CUSTOMER_REJECTED) {
                action = ConversationAction.WAIT_FOR_OPERATOR;
            } else {
                action = ConversationAction.CONFIRM_ORDER;
            }
        } else if (requested == ConversationAction.WAIT_FOR_OPERATOR || requested == ConversationAction.CUSTOMER_REJECTED) {
            action = ConversationAction.WAIT_FOR_OPERATOR;
        } else if (aiMessage != null && !aiMessage.isBlank()) {
            action = ConversationAction.ASK_CUSTOMER;
            message = aiMessage;
            review = ReviewStatus.YELLOW;
        } else {
            action = ConversationAction.WAIT_FOR_OPERATOR;
        }
        return new InterpretedOrder(items, review, action, message, reason, address, time, customerName, paymentInfo);
    }

    /** Valor crudo de la IA solo para auditoria/comparacion: si no cabe en NUMERIC(10,3) se descarta. */
    static BigDecimal storableAiQuantity(BigDecimal q) {
        if (q == null || q.abs().compareTo(new BigDecimal("9999999")) > 0) return null;
        return q.setScale(3, RoundingMode.HALF_UP);
    }

    private static BigDecimal sanitizeQuantity(BigDecimal q) {
        if (q == null || q.signum() <= 0 || q.compareTo(MAX_QTY) > 0) return null;
        return q.setScale(3, RoundingMode.HALF_UP);
    }

    /** Un mensaje al cliente nunca debe traer precios, links ni ser enorme: si los trae, se descarta. */
    public static String sanitizeReply(String msg) {
        if (msg == null) return null;
        String m = msg.strip();
        if (m.isEmpty() || m.length() > 400 || m.contains("$") || m.toLowerCase().contains("http")) return null;
        return m;
    }

    private static String oneLine(String s) { return s.replaceAll("[\\r\\n\"]+", " "); }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
