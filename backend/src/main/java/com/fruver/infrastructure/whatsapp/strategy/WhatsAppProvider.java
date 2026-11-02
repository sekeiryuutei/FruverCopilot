package com.fruver.infrastructure.whatsapp.strategy;

public enum WhatsAppProvider {
    META_CLOUD_API,
    BAILEYS_QR;

    public static WhatsAppProvider fromString(String raw) {
        if (raw == null) return BAILEYS_QR;
        try {
            return WhatsAppProvider.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return BAILEYS_QR;
        }
    }
}
