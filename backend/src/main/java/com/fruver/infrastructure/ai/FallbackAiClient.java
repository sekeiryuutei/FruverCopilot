package com.fruver.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.AIOrderResponse;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Cliente de contingencia multi-proveedor en la nube (OpenCode Zen / OpenRouter / Groq / OpenAI).
 * Se activa automáticamente si todas las keys de Gemini sufren saturación o caída de red.
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

    public AIOrderResponse tryFallback(String systemPrompt, String userPrompt) {
        // 1. Respaldo 1: OpenCode Zen (DeepSeek gratuito)
        String opencodeKey = props.ai().opencode().apiKey();
        if (isValidKey(opencodeKey)) {
            try {
                log.info("Activando Respaldo 1: OpenCode Zen ({}, modelo: {})...", props.ai().opencode().baseUrl(), props.ai().opencode().model());
                AIOrderResponse res = callOpenAiCompatible(
                        props.ai().opencode().baseUrl(),
                        opencodeKey,
                        props.ai().opencode().model(),
                        systemPrompt,
                        userPrompt
                );
                if (res != null) {
                    log.info("✓ Respaldo 1 (OpenCode Zen) exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Respaldo 1 (OpenCode) falló: {}", e.getMessage());
            }
        }

        // 2. Respaldo 2: OpenRouter (Multi-modelo: Gemini, DeepSeek, Llama en la nube)
        String openrouterKey = props.ai().openrouter().apiKey();
        if (isValidKey(openrouterKey)) {
            try {
                log.info("Activando Respaldo 2: OpenRouter (modelo: {})...", props.ai().openrouter().model());
                AIOrderResponse res = callOpenAiCompatible(
                        props.ai().openrouter().baseUrl(),
                        openrouterKey,
                        props.ai().openrouter().model(),
                        systemPrompt,
                        userPrompt
                );
                if (res != null) {
                    log.info("✓ Respaldo 2 (OpenRouter) exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Respaldo 2 (OpenRouter) falló: {}", e.getMessage());
            }
        }

        // 3. Respaldo 3: Groq (Ultra rápido)
        String groqKey = props.ai().groq().apiKey();
        if (isValidKey(groqKey)) {
            try {
                log.info("Activando Respaldo 3: Groq (modelo: {})...", props.ai().groq().model());
                AIOrderResponse res = callOpenAiCompatible(
                        props.ai().groq().baseUrl(),
                        groqKey,
                        props.ai().groq().model(),
                        systemPrompt,
                        userPrompt
                );
                if (res != null) {
                    log.info("✓ Respaldo 3 (Groq) exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Respaldo 3 (Groq) falló: {}", e.getMessage());
            }
        }

        // 4. Respaldo 4: OpenAI
        String openaiKey = props.ai().openai().apiKey();
        if (isValidKey(openaiKey) && !openaiKey.contains("not-configured")) {
            try {
                log.info("Activando Respaldo 4: OpenAI (modelo: {})...", props.ai().openai().model());
                AIOrderResponse res = callOpenAiCompatible(
                        props.ai().openai().baseUrl(),
                        openaiKey,
                        props.ai().openai().model(),
                        systemPrompt,
                        userPrompt
                );
                if (res != null) {
                    log.info("✓ Respaldo 4 (OpenAI) exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Respaldo 4 (OpenAI) falló: {}", e.getMessage());
            }
        }

        return null;
    }

    private boolean isValidKey(String key) {
        return key != null && !key.isBlank() && !key.startsWith("#") && key.strip().length() > 6;
    }

    private AIOrderResponse callOpenAiCompatible(String baseUrl, String apiKey, String model, String systemPrompt, String userPrompt) throws Exception {
        String url = baseUrl.strip();
        if (!url.endsWith("/chat/completions")) {
            if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            if (!url.endsWith("/v1")) url = url + "/v1";
            url = url + "/chat/completions";
        }

        RestClient client = restBuilder.build();
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        JsonNode root = client.post()
                .uri(url)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (root == null || !root.has("choices") || root.path("choices").isEmpty()) {
            return null;
        }

        String content = root.path("choices").get(0).path("message").path("content").asText("");
        if (content.isBlank()) return null;

        String clean = GeminiOrderExtractor.stripMarkdownFences(content);
        return mapper.readValue(clean, AIOrderResponse.class);
    }
}
