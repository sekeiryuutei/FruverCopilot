package com.fruver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String timezone,
        boolean devEndpoints,
        Conversation conversation,
        Printing printing,
        Audio audio,
        Whatsapp whatsapp,
        Ai ai,
        Stt stt,
        Security security,
        DemoSeed demoSeed) {

    public AppProperties {
        if (ai == null) ai = new Ai("gemini", new Gemini("", "gemini-2.5-flash", "https://generativelanguage.googleapis.com"));
    }

    public record Ai(String provider, Gemini gemini) {
        public Ai {
            if (provider == null || provider.isBlank()) provider = "gemini";
            if (gemini == null) gemini = new Gemini("", "gemini-2.5-flash", "https://generativelanguage.googleapis.com");
        }
    }

    public record Gemini(String apiKey, String model, String baseUrl) {
        public Gemini {
            if (model == null || model.isBlank()) model = "gemini-2.5-flash";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://generativelanguage.googleapis.com";
            if (apiKey == null) apiKey = "";
        }
    }

    public record Conversation(boolean aiAutoReply, boolean autoConfirmClearOrders) {}

    public record Printing(boolean enabled, String type, String host, int port, int columns,
                           int maxAttempts, long pollMs, String shopName) {}

    public record Audio(String storageType, String storagePath) {}

    public record Whatsapp(boolean enabled, String verifyToken, String appSecret, String accessToken,
                           String phoneNumberId, String apiVersion) {}

    public record Stt(String model, String apiKey, String baseUrl) {}

    public record DemoSeed(boolean enabled, boolean reset) {}

    public record Security(String adminUsername, String adminPassword,
                           String operatorUsername, String operatorPassword) {}
}
