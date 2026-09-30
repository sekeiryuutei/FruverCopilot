package com.fruver.application.dto;

import java.math.BigDecimal;

/** Linea cotizada por el backend. problem != null implica que la linea no puede aprobarse. */
public record QuoteLine(
        Long itemId,
        Long productId,
        String productName,
        BigDecimal quantity,
        String unit,
        BigDecimal catalogPrice,
        BigDecimal appliedPrice,
        boolean manual,
        BigDecimal subtotal,
        String problem) {}
