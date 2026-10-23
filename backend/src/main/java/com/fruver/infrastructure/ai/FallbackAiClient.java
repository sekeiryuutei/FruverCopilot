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

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cliente de contingencia multi-proveedor (OpenCode Zen / OpenRouter / Ollama local).
 * Se activa automáticamente si todas las keys de Gemini sufren saturación o caída.
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
        // 1. Probar OpenCode Zen / OpenRouter si hay clave configurada
        String opencodeKey = props.ai().opencode().apiKey();
        if (opencodeKey != null && !opencodeKey.isBlank() && !opencodeKey.startsWith("#") && opencodeKey.length() > 6) {
            try {
                log.info("Activando fallback con OpenCode Zen (modelo: {})...", props.ai().opencode().model());
                AIOrderResponse res = callOpenAiCompatible(
                        props.ai().opencode().baseUrl(),
                        opencodeKey,
                        props.ai().opencode().model(),
                        systemPrompt,
                        userPrompt
                );
                if (res != null) {
                    log.info("✓ Fallback con OpenCode Zen exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Fallback OpenCode falló: {}", e.getMessage());
            }
        }

        // 2. Probar Ollama local si está disponible
        String ollamaUrl = props.ai().ollama().baseUrl();
        if (ollamaUrl != null && !ollamaUrl.isBlank()) {
            try {
                log.info("Activando fallback con Ollama local ({}, modelo: {})...", ollamaUrl, props.ai().ollama().model());
                AIOrderResponse res = callOllama(ollamaUrl, props.ai().ollama().model(), systemPrompt, userPrompt);
                if (res != null) {
                    log.info("✓ Fallback con Ollama local exitoso");
                    return res;
                }
            } catch (Exception e) {
                log.warn("Fallback Ollama no disponible: {}", e.getMessage());
            }
        }

        return null;
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

    private AIOrderResponse callOllama(String baseUrl, String model, String systemPrompt, String userPrompt) throws Exception {
        String url = baseUrl.strip();
        if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        String chatUrl = url + "/api/chat";

        RestClient client = restBuilder.build();
        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "format", "json",
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        JsonNode root = client.post()
                .uri(chatUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (root == null || !root.has("message")) {
            return null;
        }

        String content = root.path("message").path("content").asText("");
        if (content.isBlank()) return null;

        String clean = GeminiOrderExtractor.stripMarkdownFences(content);
        return mapper.readValue(clean, AIOrderResponse.class);
    }
}
