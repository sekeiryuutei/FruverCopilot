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
            for (String m : props.ai().gemini().model().split("[,;|]")) {
                String trimmed = m.strip();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) {
                    list.add(trimmed);
                }
            }
        }
        // Modelos de respaldo estándar en orden de capacidad/velocidad a más estables
        for (String m : java.util.List.of(
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-2.0-flash",
                "gemini-1.5-flash",
                "gemini-flash-latest",
                "gemini-3.8-flash",
                "gemini-3.5-flash-lite"
        )) {
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

        return executeGemini(payload, SystemPrompts.ORDER_EXTRACTION, userPrompt, null, null);
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
                INSTRUCCIÓN DE CONSOLIDACIÓN MULTIMODAL:
                1. Extrae con precisión todos los renglones, productos y cantidades escritos en la imagen adjunta.
                2. REVISA <nota_adicional_cliente>: contiene los mensajes de texto y notas de voz transcritas enviadas por el cliente. Si menciona productos adicionales (por ejemplo zanahoria, lulo, maracuyá, pepino, etc.), DEBES EXTRAERLOS Y AGREGARLOS A LA LISTA 'items'.
                3. Si un producto se menciona en el audio/texto y también en la foto con cantidades diferentes, prevalece la cantidad más reciente.
                4. Si ya hay productos acordados en pedido_actual, consérvalos y consolida todos los ítems en una única lista completa sin omitir nada.
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

        return executeGemini(payload, SystemPrompts.IMAGE_ORDER_EXTRACTION, contextPrompt, List.of(imageBytes), List.of(cleanMime));
    }

    @Override
    public AIOrderResponse extractBatch(List<byte[]> images, List<String> mimeTypes, ExtractionRequest request) {
        if (images == null || images.isEmpty()) {
            return extract(request);
        }
        if (images.size() == 1) {
            String mime = (mimeTypes != null && !mimeTypes.isEmpty()) ? mimeTypes.get(0) : "image/jpeg";
            return extractImage(images.get(0), mime, request);
        }

        List<Map<String, Object>> parts = new java.util.ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            byte[] img = images.get(i);
            if (img == null || img.length == 0) continue;
            byte[] opt = ImageOptimizer.optimizeForAi(img);
            String b64 = Base64.getEncoder().encodeToString(opt);
            String mime = (mimeTypes != null && i < mimeTypes.size()) ? sanitizeImageMime(mimeTypes.get(i)) : "image/jpeg";
            parts.add(Map.of("inline_data", Map.of("mime_type", mime, "data", b64)));
        }

        String contextPrompt = """
                <turnos_previos>%s</turnos_previos>
                <pedido_actual>%s</pedido_actual>
                <catalogo_candidato>%s</catalogo_candidato>
                <zonas_domicilio>%s</zonas_domicilio>
                <historial_cliente>%s</historial_cliente>
                <nota_adicional_cliente>%s</nota_adicional_cliente>
                INSTRUCCIÓN DE CONSOLIDACIÓN MULTIMODAL:
                1. Lee con precisión todas las imágenes adjuntas. Si hay varias fotos o listas, consolida todos los productos sin omitir ninguno.
                2. REVISA <nota_adicional_cliente>: contiene los mensajes de texto y notas de voz transcritas enviadas por el cliente. Si menciona productos adicionales, extráelos e inclúyelos en 'items'.
                3. Si un producto se repite con diferente cantidad, conserva la cantidad más reciente.
                4. Si ya hay productos acordados en pedido_actual, consérvalos. Consolida todo en una única lista completa.
                """.formatted(
                join(request.recentTurns()),
                join(request.currentItems()),
                join(request.catalogCandidates()),
                request.deliveryZones() == null ? "(No especificado)" : request.deliveryZones(),
                request.customerHistory() == null ? "(Sin historial previo registrado)" : request.customerHistory(),
                request.message() == null ? "" : request.message());

        parts.add(Map.of("text", contextPrompt));

        Map<String, Object> payload = Map.of(
                "system_instruction", Map.of(
                        "parts", List.of(Map.of("text", SystemPrompts.IMAGE_ORDER_EXTRACTION))
                ),
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", parts
                        )
                ),
                "generationConfig", Map.of(
                        "response_mime_type", "application/json",
                        "temperature", 0.0,
                        "maxOutputTokens", 4096
                )
        );

        return executeGemini(payload, SystemPrompts.IMAGE_ORDER_EXTRACTION, contextPrompt, images, mimeTypes);
    }

    private AIOrderResponse executeGemini(Map<String, Object> payload, String systemPrompt, String userPrompt,
                                         List<byte[]> images, List<String> mimeTypes) {
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
                    log.info("✓ Gemini respondió exitosamente con modelo '{}' usando Key #{}", candidateModel, keyEntry.index() + 1);
                    break;
                } catch (org.springframework.web.client.RestClientResponseException e) {
                    lastException = e;
                    int status = e.getStatusCode().value();
                    if (status == 429) {
                        keyPool.onFailure(keyEntry.index(), 429, Duration.ofSeconds(60));
                        log.warn("Gemini Key #{} con rate limit (HTTP 429) en modelo '{}', probando siguiente key...", keyEntry.index() + 1, candidateModel);
                        continue;
                    }
                    if (status == 404) {
                        log.warn("Gemini modelo '{}' no existe o no disponible (HTTP 404), saltando a modelo de respaldo...", candidateModel);
                        break;
                    }
                    if (status == 503 && attempt < 2) {
                        log.warn("Gemini modelo '{}' con 503, reintentando...", candidateModel);
                        try { Thread.sleep(1200L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    log.warn("Gemini modelo '{}' falló (HTTP {}): {}, probando siguiente modelo...", candidateModel, status, e.getMessage());
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
                log.warn("Todos los modelos y keys de Gemini fallaron. Activando contingencia multi-proveedor (Groq/OpenRouter/OpenCode/OpenAI)...");
                AIOrderResponse fallbackResponse;
                if (images != null && !images.isEmpty()) {
                    fallbackResponse = fallbackClient.tryFallbackVision(images, mimeTypes, systemPrompt, userPrompt);
                } else {
                    fallbackResponse = fallbackClient.tryFallback(systemPrompt, userPrompt);
                }
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
