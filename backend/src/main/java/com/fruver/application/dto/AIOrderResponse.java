package com.fruver.application.dto;

import com.fruver.domain.enums.ConversationAction;
import java.util.List;

public record AIOrderResponse(
        List<ItemSugeridoDTO> items,
        ConversationAction action,
        String customerMessage,
        String clarificationReason,
        String direccion,
        String horarioEntrega,
        String nombreCliente,
        String metodoPago) {

    public AIOrderResponse(List<ItemSugeridoDTO> items, ConversationAction action, String customerMessage, String clarificationReason, String direccion, String horarioEntrega) {
        this(items, action, customerMessage, clarificationReason, direccion, horarioEntrega, null, null);
    }

    public AIOrderResponse(List<ItemSugeridoDTO> items, ConversationAction action, String customerMessage, String clarificationReason, String direccion) {
        this(items, action, customerMessage, clarificationReason, direccion, null, null, null);
    }

    public AIOrderResponse(List<ItemSugeridoDTO> items, ConversationAction action, String customerMessage, String clarificationReason) {
        this(items, action, customerMessage, clarificationReason, null, null, null, null);
    }
}
