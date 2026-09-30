package com.fruver.application.dto;

public record IntakeResult(
        long orderId,
        long messageId,
        boolean reply,
        boolean audio,
        String text,
        String mediaId,
        String storageKey,
        String mime) {}
