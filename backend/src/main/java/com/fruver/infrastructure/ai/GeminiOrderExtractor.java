package com.fruver.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.application.port.OrderExtractor;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Adaptador de IA para Google Gemini (Gemini 2.5/2.0/1.5 Flash).
 * Consume la API de Gemini vía REST con salida estructurada JSON nativa.
 * Su salida es UNTRUSTED: la valida determinísticamente ExtractionInterpreter.
 */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('gemini') && !'${app.ai.gemini.api-key:}'.trim().isEmpty() && !'${app.ai.gemini.api-key:}'.trim().startsWith('#') && '${app.ai.gemini.api-key:}'.trim().length() > 10 && !'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class GeminiOrderExtractor implements OrderExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiOrderExtractor.class);

    private final RestClient http;
    private final String model;
    private final String apiKey;
    private final ObjectMapper mapper;

    public GeminiOrderExtractor(RestClient.Builder builder, AppProperties props, ObjectMapper mapper) {
        this.http = builder.baseUrl(props.ai().gemini().baseUrl()).build();
        this.model = props.ai().gemini().model();
        this.apiKey = props.ai().gemini().apiKey();
        this.mapper = mapper;
    }

    @Override
    public AIOrderResponse extract(ExtractionRequest request) {
        String userPrompt = """
                <turnos_previos>%s</turnos_previos>
                <pedido_actual>%s</pedido_actual>
                <catalogo_candidato>%s</catalogo_candidato>
                <mensaje_cliente>%s</mensaje_cliente>
                """.formatted(
                join(request.recentTurns()),
                join(request.currentItems()),
                join(request.catalogCandidates()),
                request.message() == null ? "" : request.message());

        Map<String, Object> payload = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", SystemPrompts.ORDER_EXTRACTION))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(Map.of("text", userPrompt))
                        )
                ),
                "generationConfig", Map.of(
                        "response_mime_type", "application/json",
                        "temperature", 0.0
                )
        );

        try {
            JsonNode root = http.post()
                    .uri("/v1beta/models/" + model + ":generateContent")
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !root.has("candidates") || root.path("candidates").isEmpty()) {
                throw new ExtractionUnavailableException("Respuesta vacía o sin candidatos de Gemini", null);
            }

            JsonNode candidate = root.path("candidates").get(0);
            String rawText = candidate.path("content").path("parts").get(0).path("text").asText("");
            if (rawText.isBlank()) {
                throw new ExtractionUnavailableException("Texto de respuesta vacío de Gemini", null);
            }

            String cleanJson = stripMarkdownFences(rawText);
            AIOrderResponse response = mapper.readValue(cleanJson, AIOrderResponse.class);
            if (response == null) {
                throw new ExtractionUnavailableException("Fallo al deserializar respuesta de Gemini", null);
            }
            return response;
        } catch (ExtractionUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Fallo en extracción con Gemini: {}", e.getMessage());
            throw new ExtractionUnavailableException("Fallo la extracción con Gemini: " + e.getClass().getSimpleName(), e);
        }
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? "(ninguno)" : String.join(" | ", values);
    }

    static String stripMarkdownFences(String s) {
        if (s == null) return "";
        String t = s.strip();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.strip();
    }
}

