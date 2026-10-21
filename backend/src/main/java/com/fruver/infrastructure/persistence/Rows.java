package com.fruver.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Filas de base de datos. Se mapean por nombre de columna (snake_case -> camelCase). */
public final class Rows {
    private Rows() {}

    public record ProductRow(Long id, String name, String unit, boolean active, String imageUrl) {}

    public record ItemRow(Long id, Long orderId, int lineNo, String mentioned, String aiProductName,
                          BigDecimal aiQuantity, String aiUnit, boolean aiAmbiguous, String observation,
                          Long productId, BigDecimal quantity, String unit, BigDecimal manualPrice,
                          String priceChangeReason, boolean edited) {}

    public record OrderRow(Long id, Long customerId, String customerName, String customerPhone, String address,
                           String notes, String inputType, String status, String conversationStatus,
                           String reviewStatus, String aiStatus, String aiNote, String suggestedReply,
                           boolean corrected, int version, OffsetDateTime receivedAt, OffsetDateTime extractedAt,
                           OffsetDateTime approvedAt, String customerType, boolean hasAudio, boolean hasImage,
                           Long saleId, String printStatus, OffsetDateTime dispatchedAt, String dispatchedBy,
                           Long deliveryPersonId, String deliveryPersonName,
                           boolean requiresVerification, String verificationReason,
                           String targetPrinter, String printedPrinter) {

        public OrderRow(Long id, Long customerId, String customerName, String customerPhone, String address,
                        String notes, String inputType, String status, String conversationStatus,
                        String reviewStatus, String aiStatus, String aiNote, String suggestedReply,
                        boolean corrected, int version, OffsetDateTime receivedAt, OffsetDateTime extractedAt,
                        OffsetDateTime approvedAt, String customerType, boolean hasAudio,
                        Long saleId, String printStatus, OffsetDateTime dispatchedAt, String dispatchedBy,
                        Long deliveryPersonId, String deliveryPersonName) {
            this(id, customerId, customerName, customerPhone, address, notes, inputType, status, conversationStatus,
                    reviewStatus, aiStatus, aiNote, suggestedReply, corrected, version, receivedAt, extractedAt,
                    approvedAt, customerType, hasAudio, false, saleId, printStatus, dispatchedAt, dispatchedBy,
                    deliveryPersonId, deliveryPersonName, false, null, null, null);
        }
    }
}
