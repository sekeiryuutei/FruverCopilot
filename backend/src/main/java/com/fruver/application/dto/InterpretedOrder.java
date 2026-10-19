package com.fruver.application.dto;

import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import java.math.BigDecimal;
import java.util.List;

/** Resultado de validar la salida del LLM contra catalogo y reglas: lo unico que se persiste. */
public record InterpretedOrder(
        List<Item> items,
        ReviewStatus review,
        ConversationAction action,
        String customerMessage,
        String note,
        String address,
        String deliveryTime) {

    public InterpretedOrder(List<Item> items, ReviewStatus review, ConversationAction action, String customerMessage, String note, String address) {
        this(items, review, action, customerMessage, note, address, null);
    }

    public InterpretedOrder(List<Item> items, ReviewStatus review, ConversationAction action, String customerMessage, String note) {
        this(items, review, action, customerMessage, note, null, null);
    }

    public record Item(
            String mentioned,
            String aiProductName,
            BigDecimal aiQuantity,
            String aiUnit,
            boolean aiAmbiguous,
            String observation,
            Long productId,
            BigDecimal quantity,
            UnitOfMeasure unit) {}
}
