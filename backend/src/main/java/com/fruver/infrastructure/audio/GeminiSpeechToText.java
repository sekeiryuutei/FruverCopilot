package com.fruver.infrastructure.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fruver.application.port.SpeechToTextService;
import com.fruver.config.AppProperties;
import com.fruver.infrastructure.ai.GeminiKeyPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Adaptador de transcripción multimodal con Google Gemini (Gemini Flash).
 * Ingesta directa de tokens de audio con contexto fonético de fruver colombiano.
 * Incluye pool de API Keys con cooldown ante 429 y cadena de modelos candidatos de respaldo.
 */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('gemini') && (('${app.ai.gemini.api-key:}'.trim().length() > 8 && !'${app.ai.gemini.api-key:}'.trim().startsWith('#')) || ('${app.ai.gemini.api-keys:}'.trim().length() > 8 && !'${app.ai.gemini.api-keys:}'.trim().startsWith('#'))) && !'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class GeminiSpeechToText implements SpeechToTextService {

    private static final Logger log = LoggerFactory.getLogger(GeminiSpeechToText.class);

    private final RestClient http;
    private final List<String> candidateModels;
    private final GeminiKeyPool keyPool;

    public GeminiSpeechToText(RestClient.Builder builder, AppProperties props) {
        this.http = builder.baseUrl(props.ai().gemini().baseUrl()).build();
        java.util.List<String> list = new java.util.ArrayList<>();

        // 1. Modelos robustos para audio primero (los modelos completos no cortan en titubeos o pausas mid-utterance)
        for (String m : java.util.List.of("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")) {
            if (!list.contains(m)) list.add(m);
        }

        // 2. Si se configuró un modelo específico en propiedades, incluirlo si no está
        if (props.ai().gemini().model() != null && !props.ai().gemini().model().isBlank()) {
            for (String m : props.ai().gemini().model().split("[,;|]")) {
                String trimmed = m.strip();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }

        // 3. Fallbacks ligeros al final
        for (String m : java.util.List.of("gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.0-flash-lite", "gemini-flash-latest")) {
            if (!list.contains(m)) list.add(m);
        }

        this.candidateModels = java.util.List.copyOf(list);
        this.keyPool = new GeminiKeyPool(props.ai().gemini().apiKeys(), props.ai().gemini().apiKey());
    }

    public List<String> getCandidateModels() {
        return candidateModels;
    }

    public static String buildSystemInstruction() {
        return """
                Eres un transcriptor experto de notas de voz de WhatsApp para una tienda de frutas y verduras (Fruver) en Colombia.
                REGLAS CRÍTICAS:
                1. Transcribe el audio COMPLETO, de principio a fin, palabra por palabra, hasta el último segundo sin omitir ni cortar nada. Este audio puede durar varios minutos y contiene una lista larga de muchos productos. NO te detengas hasta transcribir la última palabra dicha.
                2. FONÉTICA Y VOCABULARIO CLAVE DE FRUVER COLOMBIANO:
                   - 'habichuela': verdura verde delgada (por libras o kilos), muy común en pedidos. Si suena similar a 'ahuyama', pero es verdura de pedido, transcribe 'habichuela'.
                   - 'guayaba': si el cliente dice guayaba, transcribe 'guayaba'.
                   - Otros términos comunes: papa criolla, papa pastusa, papa parda, papa amarilla, cebolla cabezona, cebolla larga, cilantro, cimarrón, espinaca, champiñones, lulo, maracuyá, tomate chonto, banano, plátano verde, plátano maduro, arveja, fríjol verde, fríjol, pulpa de fruta, pulpa de mango, yuca, arracacha, batata, batavia, calabacín, brócoli, piña, cidra, ulluco, pitahaya, papaya, etc.
                3. MANEJO DE TITUBEOS, DUDAS, PAUSAS Y SILENCIOS (MÁXIMA PRIORIDAD - NO CORTAR):
                   - Los clientes graban notas de voz informales mientras piensan, revisan su nevera o hablan con otra persona al lado.
                   - Es muy común que TITUBEEN A MITAD DE PALABRA o se traben (ej: "dos libras de fri... [pausa de 1 segundo] ...jol verde", "tres kilos de pa... de papa"). Si el cliente corta una sílaba o palabra y la retoma después de una pausa, RECONSTRUYE Y COMPLETA la palabra y SIGUE TRANSCRIBIENDO.
                   - NUNCA consideres un titubeo, tropiezo fonético, silencio o pausa breve como el final del audio.
                   - Si hay una pausa en el audio, OBLIGATORIAMENTE continúa escuchando y transcribiendo hasta el último segundo de la pista.
                   - Transcribe también cualquier instrucción adicional que se diga al final del audio (ej: direcciones, barrios, horarios de entrega como 'a las 6 de la tarde', 'calle 70 norte', etc.).
                4. Devuelve ÚNICAMENTE el texto transcrito sin explicaciones ni saludos.
                """;
    }

    @Override
    public String transcribe(byte[] audio, String mimeType) {
        if (audio == null || audio.length == 0) return "";
        String base64Audio = Base64.getEncoder().encodeToString(audio);
        String cleanMime = sanitizeMime(mimeType);

        String systemInstruction = buildSystemInstruction();

        Map<String, Object> payload = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", systemInstruction))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("inline_data", Map.of("mime_type", cleanMime, "data", base64Audio)),
                                        Map.of("text", "Transcribe TODO este audio completo de principio a fin, palabra por palabra. Si el hablante titubea, corta una palabra a la mitad o hace pausas o silencios, NO te detengas: escucha hasta el último segundo y transcribe todas las palabras, productos, cantidades, direcciones y horas que se digan.")
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "temperature", 0.0,
                        "maxOutputTokens", 8192
                )
        );

        JsonNode root = null;
        Exception lastException = null;

        for (String candidateModel : candidateModels) {
            boolean modelSucceeded = false;
            for (int attempt = 1; attempt <= 2; attempt++) {
                GeminiKeyPool.KeyEntry keyEntry = keyPool.next();
                try {
                    root = http.post()
                            .uri("/v1beta/models/" + candidateModel + ":generateContent")
                            .header("x-goog-api-key", keyEntry.key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(payload)
                            .retrieve()
                            .body(JsonNode.class);
                    keyPool.onSuccess(keyEntry.index());
                    modelSucceeded = true;
                    break;
                } catch (org.springframework.web.client.RestClientResponseException e) {
                    lastException = e;
                    int status = e.getStatusCode().value();
                    if (status == 429) {
                        keyPool.onFailure(keyEntry.index(), 429, Duration.ofSeconds(60));
                        log.warn("Gemini STT Key #{} con rate limit (HTTP 429), cambiando a siguiente key...", keyEntry.index() + 1);
                        continue;
                    }
                    if (status == 404) {
                        log.warn("Gemini STT modelo '{}' no disponible (HTTP 404), cambiando al modelo de respaldo...", candidateModel);
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
        return extractTextFromCandidate(root.path("candidates").get(0));
    }

    public static String extractTextFromCandidate(JsonNode candidate) {
        if (candidate == null) return "";
        JsonNode partsNode = candidate.path("content").path("parts");
        if (!partsNode.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : partsNode) {
            String pText = part.path("text").asText("");
            if (!pText.isBlank()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(pText.strip());
            }
        }
        return sb.toString().strip();
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
