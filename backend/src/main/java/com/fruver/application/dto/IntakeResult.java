package com.fruver.application.dto;

public record IntakeResult(
        long orderId,
        long messageId,
        boolean reply,
        boolean audio,
        boolean image,
        String text,
        String mediaId,
        String storageKey,
        String mime) {

    public IntakeResult(long orderId, long messageId, boolean reply, boolean audio,
                        String text, String mediaId, String storageKey, String mime) {
        this(orderId, messageId, reply, audio, false, text, mediaId, storageKey, mime);
    }
}
