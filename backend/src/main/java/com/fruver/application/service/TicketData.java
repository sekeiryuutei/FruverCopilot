package com.fruver.application.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record TicketData(
        String shop,
        long orderNumber,
        OffsetDateTime at,
        String customer,
        String phone,
        String customerType,
        List<Line> lines,
        BigDecimal total,
        String address,
        String notes,
        boolean reprint,
        String deliveryPersonName,
        boolean requiresVerification,
        String verificationReason) {

    public TicketData(
            String shop, long orderNumber, OffsetDateTime at, String customer, String phone,
            String customerType, List<Line> lines, BigDecimal total, String address, String notes, boolean reprint) {
        this(shop, orderNumber, at, customer, phone, customerType, lines, total, address, notes, reprint, null, false, null);
    }

    public TicketData(
            String shop, long orderNumber, OffsetDateTime at, String customer, String phone,
            String customerType, List<Line> lines, BigDecimal total, String address, String notes,
            boolean reprint, String deliveryPersonName) {
        this(shop, orderNumber, at, customer, phone, customerType, lines, total, address, notes, reprint, deliveryPersonName, false, null);
    }

    public record Line(String name, BigDecimal quantity, String unit, BigDecimal price, BigDecimal subtotal) {}
}
