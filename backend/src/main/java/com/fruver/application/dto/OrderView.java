package com.fruver.application.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record OrderView(
        long id,
        int version,
        String status,
        String conversationStatus,
        String reviewStatus,
        String inputType,
        String aiStatus,
        String aiNote,
        String suggestedReply,
        String confirmationDraft,
        CustomerInfo customer,
        String address,
        String notes,
        OffsetDateTime receivedAt,
        String messageText,
        boolean hasAudio,
        boolean hasImage,
        boolean corrected,
        List<ItemView> items,
        BigDecimal total,
        boolean complete,
        Long saleId,
        String printStatus,
        OffsetDateTime dispatchedAt,
        String dispatchedBy,
        int messageCount,
        Long pendingDispatchOrderId,
        Long deliveryPersonId,
        String deliveryPersonName,
        boolean requiresVerification,
        String verificationReason,
        String targetPrinter,
        String printedPrinter) {

    public OrderView(
            long id, int version, String status, String conversationStatus, String reviewStatus,
            String inputType, String aiStatus, String aiNote, String suggestedReply,
            String confirmationDraft, CustomerInfo customer, String address, String notes,
            OffsetDateTime receivedAt, String messageText, boolean hasAudio, boolean corrected,
            List<ItemView> items, BigDecimal total, boolean complete, Long saleId, String printStatus) {
        this(id, version, status, conversationStatus, reviewStatus, inputType, aiStatus, aiNote,
                suggestedReply, confirmationDraft, customer, address, notes, receivedAt, messageText,
                hasAudio, false, corrected, items, total, complete, saleId, printStatus, null, null, 0, null, null, null,
                false, null, null, null);
    }

    public OrderView(
            long id, int version, String status, String conversationStatus, String reviewStatus,
            String inputType, String aiStatus, String aiNote, String suggestedReply,
            String confirmationDraft, CustomerInfo customer, String address, String notes,
            OffsetDateTime receivedAt, String messageText, boolean hasAudio, boolean corrected,
            List<ItemView> items, BigDecimal total, boolean complete, Long saleId, String printStatus,
            OffsetDateTime dispatchedAt, String dispatchedBy, int messageCount, Long pendingDispatchOrderId,
            Long deliveryPersonId, String deliveryPersonName) {
        this(id, version, status, conversationStatus, reviewStatus, inputType, aiStatus, aiNote,
                suggestedReply, confirmationDraft, customer, address, notes, receivedAt, messageText,
                hasAudio, false, corrected, items, total, complete, saleId, printStatus, dispatchedAt, dispatchedBy,
                messageCount, pendingDispatchOrderId, deliveryPersonId, deliveryPersonName, false, null, null, null);
    }

    /** Telefono enmascarado: la pantalla no necesita el numero completo. */
    public record CustomerInfo(Long id, String name, String phoneMasked, String type) {}
}
