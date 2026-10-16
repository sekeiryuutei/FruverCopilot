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
 */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('gemini') && !'${app.ai.gemini.api-key:}'.trim().isEmpty() && !'${app.ai.gemini.api-key:}'.trim().startsWith('#') && '${app.ai.gemini.api-key:}'.trim().length() > 10 && !'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class GeminiSpeechToText implements SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(GeminiSpeechToText.class);

    private final RestClient http;
    private final String model;
    private final String apiKey;

    public GeminiSpeechToText(RestClient.Builder builder, AppProperties props) {
        this.http = builder.baseUrl(props.ai().gemini().baseUrl()).build();
        this.model = props.ai().gemini().model();
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

        try {
            JsonNode root = http.post()
                    .uri("/v1beta/models/" + model + ":generateContent")
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);

            if (root == null || !root.has("candidates") || root.path("candidates").isEmpty()) {
                return "";
            }
            return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText("").strip();
        } catch (Exception e) {
            log.warn("Fallo transcripción de audio con Gemini: {}", e.getMessage());
            throw new RuntimeException("Fallo transcripción con Gemini: " + e.getMessage(), e);
        }
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
