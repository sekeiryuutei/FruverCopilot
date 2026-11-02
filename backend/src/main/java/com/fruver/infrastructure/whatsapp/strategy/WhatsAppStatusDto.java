package com.fruver.infrastructure.whatsapp.strategy;

public record WhatsAppStatusDto(
        String provider,
        String status,
        String phone,
        String qrCodeBase64
) {}
