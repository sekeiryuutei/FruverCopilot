package com.fruver.application.dto;

import com.fruver.domain.enums.ConversationAction;
import java.util.List;

public record AIOrderResponse(
        List<ItemSugeridoDTO> items,
        ConversationAction action,
        String customerMessage,
        String clarificationReason) {}
