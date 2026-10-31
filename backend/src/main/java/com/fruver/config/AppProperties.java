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
        if (timezone == null || timezone.isBlank()) timezone = "America/Bogota";
        if (ai == null) ai = new Ai("gemini", new Gemini("", "", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com"), null, null, null, null, null);
    }

    public record Ai(String provider, Gemini gemini, OpenCode opencode, OpenRouter openrouter, Groq groq, OpenAiConfig openai, Ollama ollama) {
        public Ai {
            if (provider == null || provider.isBlank()) provider = "gemini";
            if (gemini == null) gemini = new Gemini("", "", "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com");
            if (opencode == null) opencode = new OpenCode("", "deepseek-v4.1-flash-free", "https://opencode.ai/inference");
            if (openrouter == null) openrouter = new OpenRouter("", "nvidia/nemotron-3.5-super-vl:free", "https://openrouter.ai/api/v1");
            if (groq == null) groq = new Groq("", "openai/gpt-oss-120b", "https://api.groq.com/openai/v1");
            if (openai == null) openai = new OpenAiConfig("", "gpt-4o-mini", "https://api.openai.com/v1");
            if (ollama == null) ollama = new Ollama("http://localhost:11434", "llama3.2");
        }
    }

    public record Gemini(String apiKey, String apiKeys, String model, String baseUrl) {
        public Gemini {
            if (model == null || model.isBlank()) model = "gemini-3.5-flash-lite";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://generativelanguage.googleapis.com";
            if (apiKey == null) apiKey = "";
            if (apiKeys == null) apiKeys = "";
        }
    }

    public record OpenCode(String apiKey, String model, String baseUrl) {
        public OpenCode {
            if (apiKey == null) apiKey = "";
            if (model == null || model.isBlank()) model = "deepseek-v4.1-flash-free";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://opencode.ai/inference";
        }
    }

    public record OpenRouter(String apiKey, String model, String baseUrl) {
        public OpenRouter {
            if (apiKey == null) apiKey = "";
            if (model == null || model.isBlank()) model = "google/gemini-2.5-flash";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://openrouter.ai/api/v1";
        }
    }

    public record Groq(String apiKey, String model, String baseUrl) {
        public Groq {
            if (apiKey == null) apiKey = "";
            if (model == null || model.isBlank()) model = "llama-3.3-70b-versatile";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.groq.com/openai/v1";
        }
    }

    public record OpenAiConfig(String apiKey, String model, String baseUrl) {
        public OpenAiConfig {
            if (apiKey == null) apiKey = "";
            if (model == null || model.isBlank()) model = "gpt-4o-mini";
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "https://api.openai.com/v1";
        }
    }

    public record Ollama(String baseUrl, String model) {
        public Ollama {
            if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://localhost:11434";
            if (model == null || model.isBlank()) model = "llama3.2";
        }
    }

    public record Conversation(boolean aiAutoReply, boolean autoConfirmClearOrders, int debounceSeconds, int debounceMaxSeconds) {
        public Conversation {
            if (debounceSeconds < 0) debounceSeconds = 20;
            if (debounceMaxSeconds < debounceSeconds) debounceMaxSeconds = Math.max(90, debounceSeconds);
        }
    }

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
            boolean autoPrintWholesale,
            int wholesaleGraceSeconds) {

        public Printing {
            if (shopName == null || shopName.isBlank()) shopName = "FRUVER EL OASIS";
            if (officeHost == null || officeHost.isBlank()) officeHost = (host != null && !host.isBlank()) ? host : "127.0.0.1";
            if (officePort <= 0) officePort = (port > 0) ? port : 9100;
            if (homeHost == null || homeHost.isBlank()) homeHost = (host != null && !host.isBlank()) ? host : "127.0.0.1";
            if (homePort <= 0) homePort = (port > 0) ? port : 9100;
            if (wholesaleGraceSeconds < 0) wholesaleGraceSeconds = 300;
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
