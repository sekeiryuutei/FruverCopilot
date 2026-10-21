package com.fruver.infrastructure.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fruver.application.port.SpeechToTextService;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Adaptador de transcripción multimodal con Google Gemini (Gemini Flash).
 * Ingesta directa de tokens de audio con contexto fonético de fruver colombiano.
 * Incluye cadena de modelos candidatos de respaldo en caso de 503 o 429.
 */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('gemini') && !'${app.ai.gemini.api-key:}'.trim().isEmpty() && !'${app.ai.gemini.api-key:}'.trim().startsWith('#') && '${app.ai.gemini.api-key:}'.trim().length() > 10 && !'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class GeminiSpeechToText implements SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(GeminiSpeechToText.class);

    private final RestClient http;
    private final List<String> candidateModels;
    private final String apiKey;

    public GeminiSpeechToText(RestClient.Builder builder, AppProperties props) {
        this.http = builder.baseUrl(props.ai().gemini().baseUrl()).build();
        java.util.List<String> list = new java.util.ArrayList<>();
        if (props.ai().gemini().model() != null && !props.ai().gemini().model().isBlank()) {
            list.add(props.ai().gemini().model().strip());
        }
        for (String m : java.util.List.of("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest")) {
            if (!list.contains(m)) list.add(m);
        }
        this.candidateModels = java.util.List.copyOf(list);
        this.apiKey = props.ai().gemini().apiKey();
    }

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        if (audio == null || audio.length == 0) return "";
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        String cleanMime = sanitizeMime(mimeType);

        Map<String, Object> payload = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text",
                                "Eres un transcriptor experto de notas de voz de WhatsApp para una tienda de frutas y verduras (Fruver) en Colombia. " +
                                "Transcribe el audio palabra por palabra con precisión, respetando términos colombianos como piña, papa criolla, " +
                                "tomate chonto, cebolla cabezona, plátano, etc. Devuelve ÚNICAMENTE el texto transcrito sin explicaciones ni saludos."))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("inline_data", Map.of("mime_type", cleanMime, "data", base64Audio)),
                                        Map.of("text", "Transcribe este audio de WhatsApp en español colombiano.")
                                )
                        )
                ),
                "generationConfig", Map.of("temperature", 0.0)
        );

        JsonNode root = null;
        Exception lastException = null;

        for (String candidateModel : candidateModels) {
            boolean modelSucceeded = false;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    root = http.post()
                            .uri("/v1beta/models/" + candidateModel + ":generateContent")
                            .header("x-goog-api-key", apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(payload)
                            .retrieve()
                            .body(JsonNode.class);
                    modelSucceeded = true;
                    break;
                } catch (org.springframework.web.client.RestClientResponseException e) {
                    lastException = e;
                    int status = e.getStatusCode().value();
                    if (status == 429 || status == 404) {
                        log.warn("Gemini STT modelo '{}' no disponible (HTTP {}), cambiando al modelo de respaldo...", candidateModel, status);
                        break;
                    }
                    if (status == 503 && attempt < 2) {
                        log.warn("Gemini STT modelo '{}' ocupado (503), reintentando...", candidateModel);
                        try {
                            Thread.sleep(1200L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrumpido durante reintento de transcripción Gemini", ie);
                        }
                        continue;
                    }
                    log.warn("Gemini STT modelo '{}' falló (HTTP {}): {}", candidateModel, status, e.getMessage());
                    break;
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Error en Gemini STT modelo '{}': {}", candidateModel, e.getMessage());
                    break;
                }
            }
            if (modelSucceeded && root != null) {
                break;
            }
        }

        if (root == null || !root.has("candidates") || root.path("candidates").isEmpty()) {
            if (lastException != null) {
                log.warn("Fallo transcripción de audio con todos los modelos Gemini: {}", lastException.getMessage());
            }
            return "";
        }
        return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText("").strip();
    }

    public static String sanitizeMime(String mime) {
        if (mime == null || mime.isBlank()) return "audio/ogg";
        int semi = mime.indexOf(';');
        String base = semi > 0 ? mime.substring(0, semi).strip() : mime.strip();
        if ("audio/ogg".equalsIgnoreCase(base) || "audio/opus".equalsIgnoreCase(base)) return "audio/ogg";
        if ("audio/mp4".equalsIgnoreCase(base) || "audio/m4a".equalsIgnoreCase(base)) return "audio/mp4";
        if ("audio/wav".equalsIgnoreCase(base) || "audio/x-wav".equalsIgnoreCase(base)) return "audio/wav";
        if ("audio/mpeg".equalsIgnoreCase(base) || "audio/mp3".equalsIgnoreCase(base)) return "audio/mp3";
        return base;
    }
}
