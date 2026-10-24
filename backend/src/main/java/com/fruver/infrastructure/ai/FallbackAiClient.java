package com.fruver.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.AIOrderResponse;
import com.fruver.config.AppProperties;
import com.fruver.infrastructure.media.ImageOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Cliente de contingencia multi-proveedor en la nube (Groq / OpenRouter / OpenCode Zen / OpenAI).
 * Se activa automáticamente si todas las keys y modelos de Gemini sufren saturación o caída.
 * Soporta tanto extracción de texto/audio como extracción visual de imágenes (listas en foto).
 */
@Component
public class FallbackAiClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackAiClient.class);

    private final AppProperties props;
    private final RestClient.Builder restBuilder;
    private final ObjectMapper mapper;

    public FallbackAiClient(AppProperties props, RestClient.Builder restBuilder, ObjectMapper mapper) {
        this.props = props;
        this.restBuilder = restBuilder;
        this.mapper = mapper;
    }

    /**
     * Contingencia para extracción de texto o transcripciones de audio.
     * Prioridad: Groq (ultra rápido < 100ms) -> OpenRouter -> OpenCode -> OpenAI.
     */
    public AIOrderResponse tryFallback(String systemPrompt, String userPrompt) {
        // 1. Respaldo 1: Groq (Ultra rápido y gratuito)
        String groqKey = props.ai().groq().apiKey();
        if (isValidKey(groqKey)) {
            List<String> groqModels = parseModels(
                    props.ai().groq().model(),
                    List.of("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b")
            );
            for (String model : groqModels) {
                try {
                    log.info("Activando Respaldo 1: Groq ({}, modelo: {})...", props.ai().groq().baseUrl(), model);
                    AIOrderResponse res = callOpenAiCompatible(
                            props.ai().groq().baseUrl(),
                            groqKey,
                            model,
                            systemPrompt,
                            userPrompt
                    );
                    if (res != null) {
                        log.info("✓ Respaldo 1 (Groq - {}) exitoso", model);
                        return res;
                    }
                } catch (Exception e) {
                    log.warn("Respaldo 1 (Groq - modelo {}) falló: {}. Probando siguiente modelo...", model, e.getMessage());
                }
            }
        }

        // 2. Respaldo 2: OpenRouter (Multi-modelo gratuito en la nube)
        String openrouterKey = props.ai().openrouter().apiKey();
        if (isValidKey(openrouterKey)) {
            List<String> orModels = parseModels(
                    props.ai().openrouter().model(),
                    List.of("nvidia/nemotron-3.5-lightning:free", "google/gemma-4-26b-a4b-it:free", "deepseek/deepseek-chat")
            );
            for (String model : orModels) {
                try {
                    log.info("Activando Respaldo 2: OpenRouter (modelo: {})...", model);
                    AIOrderResponse res = callOpenAiCompatible(
                            props.ai().openrouter().baseUrl(),
                            openrouterKey,
                            model,
                            systemPrompt,
                            userPrompt
                    );
                    if (res != null) {
                        log.info("✓ Respaldo 2 (OpenRouter - {}) exitoso", model);
                        return res;
                    }
                } catch (Exception e) {
                    log.warn("Respaldo 2 (OpenRouter - modelo {}) falló: {}. Probando siguiente modelo...", model, e.getMessage());
                }
            }
        }

        // 3. Respaldo 3: OpenCode Zen
        String opencodeKey = props.ai().opencode().apiKey();
        if (isValidKey(opencodeKey)) {
            List<String> ocModels = parseModels(
                    props.ai().opencode().model(),
                    List.of("deepseek-chat", "deepseek-v4.1-flash-free")
            );
            for (String model : ocModels) {
                try {
                    log.info("Activando Respaldo 3: OpenCode ({}, modelo: {})...", props.ai().opencode().baseUrl(), model);
                    AIOrderResponse res = callOpenAiCompatible(
                            props.ai().opencode().baseUrl(),
                            opencodeKey,
                            model,
                            systemPrompt,
                            userPrompt
                    );
                    if (res != null) {
                        log.info("✓ Respaldo 3 (OpenCode - {}) exitoso", model);
                        return res;
                    }
                } catch (Exception e) {
                    log.warn("Respaldo 3 (OpenCode - modelo {}) falló: {}. Probando siguiente modelo...", model, e.getMessage());
                }
            }
        }

        // 4. Respaldo 4: OpenAI
        String openaiKey = props.ai().openai().apiKey();
        if (isValidKey(openaiKey) && !openaiKey.contains("not-configured")) {
            List<String> oaiModels = parseModels(
                    props.ai().openai().model(),
                    List.of("gpt-4o-mini", "gpt-3.5-turbo")
            );
            for (String model : oaiModels) {
                try {
                    log.info("Activando Respaldo 4: OpenAI (modelo: {})...", model);
                    AIOrderResponse res = callOpenAiCompatible(
                            props.ai().openai().baseUrl(),
                            openaiKey,
                            model,
                            systemPrompt,
                            userPrompt
                    );
                    if (res != null) {
                        log.info("✓ Respaldo 4 (OpenAI - {}) exitoso", model);
                        return res;
                    }
                } catch (Exception e) {
                    log.warn("Respaldo 4 (OpenAI - modelo {}) falló: {}. Probando siguiente modelo...", model, e.getMessage());
                }
            }
        }

        return null;
    }

    /**
     * Contingencia para extracción visual cuando el cliente envía fotos de listas escritas a mano.
     * Requiere modelos con capacidad de visión multimodal (OpenRouter Vision / OpenAI).
     */
    public AIOrderResponse tryFallbackVision(List<byte[]> images, List<String> mimes, String systemPrompt, String userPrompt) {
        if (images == null || images.isEmpty()) {
            return tryFallback(systemPrompt, userPrompt);
        }

        // 1. Respaldo Visual 1: OpenRouter (Modelos gratuitos con visión: nemotron-3.5-super-vl, gemma-4-26b-it)
        String openrouterKey = props.ai().openrouter().apiKey();
        if (isValidKey(openrouterKey)) {
            List<String> visionModels = List.of(
                    "nvidia/nemotron-3.5-super-vl:free",
                    "google/gemma-4-26b-a4b-it:free",
                    "google/gemma-4-31b-it:free"
            );
            for (String model : visionModels) {
                try {
                    log.info("Activando Respaldo Visual 1: OpenRouter ({}, {} imagen(es))...", model, images.size());
                    AIOrderResponse res = callOpenAiCompatibleVision(
                            props.ai().openrouter().baseUrl(),
                            openrouterKey,
                            model,
                            systemPrompt,
                            userPrompt,
                            images,
                            mimes
                    );
                    if (res != null) {
                        log.info("✓ Respaldo Visual 1 (OpenRouter - {}) exitoso", model);
                        return res;
                    }
                } catch (Exception e) {
                    log.warn("Respaldo Visual 1 (OpenRouter - {}) falló: {}. Probando siguiente modelo...", model, e.getMessage());
                }
            }
        }

        // 2. Respaldo Visual 2: OpenAI (gpt-4o-mini con visión)
        String openaiKey = props.ai().openai().apiKey();
        if (isValidKey(openaiKey) && !openaiKey.contains("not-configured")) {
            try {
                log.info("Activando Respaldo Visual 2: OpenAI (gpt-4o-mini)...");
                AIOrderResponse res = callOpenAiCompatibleVision(
                        props.ai().openai().baseUrl(),
                        openaiKey,
                        "gpt-4o-mini",
                        systemPrompt,
                        userPrompt,
                        images,
                        mimes
                );
                if (res != null) {
                    log.info("✓ Respaldo Visual 2 (OpenAI gpt-4o-mini) exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Respaldo Visual 2 (OpenAI) falló: {}", e.getMessage());
            }
        }

        // Si fallan los motores visuales, intentar como texto con el prompt
        return tryFallback(systemPrompt, userPrompt);
    }

    private List<String> parseModels(String configured, List<String> defaults) {
        List<String> list = new java.util.ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            for (String m : configured.split("[,;|]")) {
                String trimmed = m.strip();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }
        for (String def : defaults) {
            if (!list.contains(def)) {
                list.add(def);
            }
        }
        return list;
    }

    private boolean isValidKey(String key) {
        return key != null && !key.isBlank() && !key.startsWith("#") && key.strip().length() > 6;
    }

    private AIOrderResponse callOpenAiCompatible(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt) throws Exception {
        String url = normalizeUrl(baseUrl);

        RestClient client = restBuilder.build();
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        JsonNode root = client.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.USER_AGENT, "FruverCopilot/1.0 (Windows NT 10.0)")
                .header("HTTP-Referer", "https://fruvercopilot.local")
                .header("X-Title", "FruverCopilot")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return parseResponse(root);
    }

    private AIOrderResponse callOpenAiCompatibleVision(
            String baseUrl, String apiKey, String model,
            String systemPrompt, String userPrompt,
            List<byte[]> images, List<String> mimes) throws Exception {

        String url = normalizeUrl(baseUrl);
        RestClient client = restBuilder.build();

        List<Map<String, Object>> contentList = new java.util.ArrayList<>();
        contentList.add(Map.of("type", "text", "text", userPrompt));

        for (int i = 0; i < images.size(); i++) {
            byte[] img = images.get(i);
            if (img == null || img.length == 0) continue;
            byte[] opt = ImageOptimizer.optimizeForAi(img);
            String b64 = Base64.getEncoder().encodeToString(opt);
            String mime = (mimes != null && i < mimes.size()) ? mimes.get(i) : "image/jpeg";
            contentList.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", "data:" + mime + ";base64," + b64)
            ));
        }

        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", contentList)
                )
        );

        JsonNode root = client.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .header(HttpHeaders.USER_AGENT, "FruverCopilot/1.0 (Windows NT 10.0)")
                .header("HTTP-Referer", "https://fruvercopilot.local")
                .header("X-Title", "FruverCopilot")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return parseResponse(root);
    }

    private String normalizeUrl(String baseUrl) {
        String url = baseUrl.strip();
        if (!url.endsWith("/chat/completions")) {
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (!url.endsWith("/v1")) url = url + "/v1";
            url = url + "/chat/completions";
        }
        return url;
    }

    private AIOrderResponse parseResponse(JsonNode root) throws Exception {
        if (root == null || !root.has("choices") || root.path("choices").isEmpty()) {
            return null;
        }

        String content = root.path("choices").get(0).path("message").path("content").asText("");
        if (content.isBlank()) return null;

        String clean = GeminiOrderExtractor.stripMarkdownFences(content);
        return mapper.readValue(clean, AIOrderResponse.class);
    }
}
