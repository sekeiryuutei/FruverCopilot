package com.fruver.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.application.port.OrderExtractor;
import com.fruver.config.AppProperties;
import com.fruver.infrastructure.media.ImageOptimizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Adaptador de IA para Google Gemini (Gemini Flash-Lite / Flash).
 * Cuenta con un pool de API Keys para balancear solicitudes entre múltiples cuentas
 * y superar holgadamente la cuota de 15 RPM en picos de demanda.
 * Si todas las keys de Gemini se saturan, activa fallback automático con OpenCode Zen u Ollama.
 * Además, optimiza automáticamente fotos de listas a máx 768px para reducir drásticamente el consumo de tokens.
 */
@Service
@ConditionalOnExpression("'${app.ai.provider:gemini}'.equalsIgnoreCase('gemini') && (('${app.ai.gemini.api-key:}'.trim().length() > 8 && !'${app.ai.gemini.api-key:}'.trim().startsWith('#')) || ('${app.ai.gemini.api-keys:}'.trim().length() > 8 && !'${app.ai.gemini.api-keys:}'.trim().startsWith('#'))) && !'${spring.ai.openai.chat.options.model:}'.equalsIgnoreCase('mock')")
public class GeminiOrderExtractor implements OrderExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiOrderExtractor.class);

    private final RestClient http;
    private final List<String> candidateModels;
    private final GeminiKeyPool keyPool;
    private final ObjectMapper mapper;
    private final FallbackAiClient fallbackClient;

    public GeminiOrderExtractor(RestClient.Builder builder, AppProperties props, ObjectMapper mapper) {
        this(builder, props, mapper, null);
    }

    @Autowired
    public GeminiOrderExtractor(RestClient.Builder builder, AppProperties props, ObjectMapper mapper,
                                @Autowired(required = false) FallbackAiClient fallbackClient) {
        this.http = builder.baseUrl(props.ai().gemini().baseUrl()).build();
        java.util.List<String> list = new java.util.ArrayList<>();
        if (props.ai().gemini().model() != null && !props.ai().gemini().model().isBlank()) {
            list.add(props.ai().gemini().model().strip());
        }
        for (String m : java.util.List.of("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-3.7-flash")) {
            if (!list.contains(m)) list.add(m);
        }
        this.candidateModels = java.util.List.copyOf(list);
        this.keyPool = new GeminiKeyPool(props.ai().gemini().apiKeys(), props.ai().gemini().apiKey());
        this.mapper = mapper;
        this.fallbackClient = fallbackClient;
    }

    @Override
    public AIOrderResponse extract(ExtractionRequest request) {
        String userPrompt = """
                <turnos_previos>%s</turnos_previos>
                <pedido_actual>%s</pedido_actual>
                <catalogo_candidato>%s</catalogo_candidato>
                <zonas_domicilio>%s</zonas_domicilio>
                <historial_cliente>%s</historial_cliente>
                <mensaje_cliente>%s</mensaje_cliente>
                """.formatted(
                join(request.recentTurns()),
                join(request.currentItems()),
                join(request.catalogCandidates()),
                request.deliveryZones() == null ? "(No especificado)" : request.deliveryZones(),
                request.customerHistory() == null ? "(Sin historial previo registrado)" : request.customerHistory(),
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
                        "temperature", 0.0,
                        "maxOutputTokens", 4096
                )
        );

        return executeGemini(payload, SystemPrompts.ORDER_EXTRACTION, userPrompt);
    }

    @Override
    public AIOrderResponse extractImage(byte[] imageBytes, String mimeType, ExtractionRequest request) {
        if (imageBytes == null || imageBytes.length == 0) {
            return extract(request);
        }

        // Redimensionar foto si supera 768px para ahorrar tokens y acelerar respuesta
        byte[] optimizedBytes = ImageOptimizer.optimizeForAi(imageBytes);
        String base64Image = Base64.getEncoder().encodeToString(optimizedBytes);
        String cleanMime = sanitizeImageMime(mimeType);

        String contextPrompt = """
                <turnos_previos>%s</turnos_previos>
                <pedido_actual>%s</pedido_actual>
                <catalogo_candidato>%s</catalogo_candidato>
                <zonas_domicilio>%s</zonas_domicilio>
                <historial_cliente>%s</historial_cliente>
                <nota_adicional_cliente>%s</nota_adicional_cliente>
                Lee con precisión los productos y cantidades de la lista en la imagen adjunta. Si ya hay productos acordados en pedido_actual, consérvalos y agrega los nuevos ítems de la lista.
                """.formatted(
                join(request.recentTurns()),
                join(request.currentItems()),
                join(request.catalogCandidates()),
                request.deliveryZones() == null ? "(No especificado)" : request.deliveryZones(),
                request.customerHistory() == null ? "(Sin historial previo registrado)" : request.customerHistory(),
                request.message() == null ? "" : request.message());

        Map<String, Object> payload = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", SystemPrompts.IMAGE_ORDER_EXTRACTION))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("inline_data", Map.of("mime_type", cleanMime, "data", base64Image)),
                                        Map.of("text", contextPrompt)
                                )
                        )
                ),
                "generationConfig", Map.of(
                        "response_mime_type", "application/json",
                        "temperature", 0.0,
                        "maxOutputTokens", 4096
                )
        );

        return executeGemini(payload, SystemPrompts.IMAGE_ORDER_EXTRACTION, contextPrompt);
    }

    private AIOrderResponse executeGemini(Map<String, Object> payload, String systemPrompt, String userPrompt) {
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
                        log.warn("Gemini Key #{} con rate limit (HTTP 429), cambiando a siguiente key...", keyEntry.index() + 1);
                        continue;
                    }
                    if (status == 404) {
                        log.warn("Gemini modelo '{}' no encontrado (HTTP 404), cambiando al modelo de respaldo...", candidateModel);
                        break;
                    }
                    if (status == 503 && attempt < 2) {
                        log.warn("Gemini modelo '{}' con 503, reintentando...", candidateModel);
                        try { Thread.sleep(1200L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    log.warn("Gemini modelo '{}' falló (HTTP {}): {}", candidateModel, status, e.getMessage());
                    break;
                } catch (org.springframework.web.client.ResourceAccessException e) {
                    lastException = e;
                    log.warn("Error de conexión con Gemini modelo '{}': {}", candidateModel, e.getMessage());
                    if (attempt < 2) {
                        try { Thread.sleep(1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                } catch (Exception e) {
                    lastException = e;
                    log.warn("Error inesperado en Gemini modelo '{}': {}", candidateModel, e.getMessage());
                    break;
                }
            }
            if (modelSucceeded && root != null) {
                break;
            }
        }

        // Si todos los modelos y keys de Gemini fallaron, activar fallback de contingencia
        if (root == null) {
            if (fallbackClient != null) {
                log.warn("Todos los modelos y keys de Gemini fallaron. Activando proveedores de contingencia (OpenCode/Ollama)...");
                AIOrderResponse fallbackResponse = fallbackClient.tryFallback(systemPrompt, userPrompt);
                if (fallbackResponse != null) {
                    return fallbackResponse;
                }
            }
            String msg = lastException != null ? lastException.getClass().getSimpleName() : "sin respuesta";
            throw new ExtractionUnavailableException("Fallo la extracción con Gemini en todos los modelos: " + msg, lastException);
        }

        try {
            if (!root.has("candidates") || root.path("candidates").isEmpty()) {
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
            log.warn("Fallo al procesar JSON de Gemini: {}", e.getMessage());
            throw new ExtractionUnavailableException("Respuesta de Gemini no parseable como JSON", e);
        }
    }

    public static String stripMarkdownFences(String text) {
        String s = text.strip();
        if (s.startsWith("```json")) {
            s = s.substring(7);
        } else if (s.startsWith("```")) {
            s = s.substring(3);
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private static String sanitizeImageMime(String mime) {
        if (mime == null || mime.isBlank()) return "image/jpeg";
        int semi = mime.indexOf(';');
        String base = semi > 0 ? mime.substring(0, semi).strip() : mime.strip();
        if ("image/jpg".equalsIgnoreCase(base) || "image/jpeg".equalsIgnoreCase(base)) return "image/jpeg";
        if ("image/png".equalsIgnoreCase(base)) return "image/png";
        if ("image/webp".equalsIgnoreCase(base)) return "image/webp";
        return base;
    }

    private static String join(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        return String.join("\n", items);
    }
}
