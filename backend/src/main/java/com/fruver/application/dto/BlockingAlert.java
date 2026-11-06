package com.fruver.application.dto;

import java.time.Instant;

public record BlockingAlert(
        String id,
        String type,        // CRITICAL, WARNING
        String title,
        String message,
        Long orderId,
        Long saleId,
        Instant createdAt
) {}

