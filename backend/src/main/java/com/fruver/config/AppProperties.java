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

    public record Printing(
            boolean enabled,
            String type,
            String host,
            int port,
            int columns,
            int maxAttempts,
            long pollMs,
            String shopName,
            String officeHost,
            int officePort,
            String homeHost,
            int homePort,
            boolean autoPrintWholesale) {

        public Printing {
            if (shopName == null || shopName.isBlank()) shopName = "FRUVER EL OASIS";
            if (officeHost == null || officeHost.isBlank()) officeHost = (host != null && !host.isBlank()) ? host : "127.0.0.1";
            if (officePort <= 0) officePort = (port > 0) ? port : 9100;
            if (homeHost == null || homeHost.isBlank()) homeHost = (host != null && !host.isBlank()) ? host : "127.0.0.1";
            if (homePort <= 0) homePort = (port > 0) ? port : 9100;
        }

        public String currentTargetPrinter(String timezone) {
            java.time.ZoneId zone = (timezone != null && !timezone.isBlank()) ? java.time.ZoneId.of(timezone) : java.time.ZoneId.of("America/Bogota");
            int hour = java.time.LocalTime.now(zone).getHour();
            // 4 am a 7 pm (04:00 a 18:59) en la oficina / fruver. El resto (19:00 a 03:59) en la casa
            return (hour >= 4 && hour < 19) ? "OFICINA" : "CASA";
        }

        public String hostFor(String location) {
            if ("CASA".equalsIgnoreCase(location)) return homeHost();
            return officeHost();
        }

        public int portFor(String location) {
            if ("CASA".equalsIgnoreCase(location)) return homePort();
            return officePort();
        }
    }

    public record Audio(String storageType, String storagePath) {}

    public record Whatsapp(boolean enabled, String verifyToken, String appSecret, String accessToken,
                           String phoneNumberId, String apiVersion) {}

    public record Stt(String model, String apiKey, String baseUrl) {}

    public record DemoSeed(boolean enabled, boolean reset) {}

    public record Security(String adminUsername, String adminPassword,
                           String operatorUsername, String operatorPassword) {}
}
