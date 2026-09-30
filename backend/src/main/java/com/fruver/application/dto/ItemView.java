package com.fruver.application.dto;

import java.math.BigDecimal;

public record ItemView(
        Long id,
        Long productId,
        String productName,
        String mentioned,
        String observation,
        boolean ambiguous,
        String aiSummary,
        BigDecimal quantity,
        String unit,
        BigDecimal catalogPrice,
        BigDecimal manualPrice,
        String priceChangeReason,
        BigDecimal appliedPrice,
        BigDecimal subtotal,
        String problem) {}
