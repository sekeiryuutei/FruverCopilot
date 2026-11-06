package com.fruver.infrastructure.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fruver.application.service.OrderBatchingCoordinator;
import com.fruver.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeminiSpeechToTextPromptAndDebounceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("El prompt de STT debe incluir la regla anti-titubeos y anti-pausas para no cortar audios")
    void testSystemInstructionContainsAntiPauseRule() {
        String instruction = GeminiSpeechToText.buildSystemInstruction();

        assertNotNull(instruction);
        assertTrue(instruction.contains("MANEJO DE TITUBEOS, DUDAS, PAUSAS Y SILENCIOS"),
                "Debe incluir la sección crítica de titubeos y silencios");
        assertTrue(instruction.contains("TITUBEEN A MITAD DE PALABRA"),
                "Debe instruir expresamente al modelo a tolerar titubeos a mitad de palabra");
        assertTrue(instruction.contains("RECONSTRUYE Y COMPLETA"),
                "Debe instruir a reconstruir palabras incompletas tras pausa");
        assertTrue(instruction.contains("NUNCA consideres un titubeo, tropiezo fonético, silencio o pausa breve como el final del audio"),
                "Debe prohibir expresamente detenerse en titubeos o pausas breves");
        assertTrue(instruction.contains("Transcribe el audio COMPLETO"),
                "Debe exigir transcripción completa de principio a fin");

        // Vocabulario de fruver verificado
        assertTrue(instruction.contains("fríjol verde"), "Debe incluir fríjol verde");
        assertTrue(instruction.contains("pulpa de mango"), "Debe incluir pulpa de mango");
        assertTrue(instruction.contains("cebolla larga"), "Debe incluir cebolla larga");
        assertTrue(instruction.contains("papa amarilla"), "Debe incluir papa amarilla");
        assertTrue(instruction.contains("papaya"), "Debe incluir papaya");
        assertTrue(instruction.contains("arveja"), "Debe incluir arveja");
    }

    @Test
    @DisplayName("Modelos candidatos para STT deben priorizar modelos robustos sobre variantes lite")
    void testCandidateModelsPrioritizesRobustModelsForAudio() {
        AppProperties.Gemini gemini = new AppProperties.Gemini("dummy-key", null, "gemini-3.5-flash-lite", "https://generativelanguage.googleapis.com");
        AppProperties.Ai ai = new AppProperties.Ai("gemini", gemini, null, null, null, null, null);
        AppProperties props = new AppProperties("America/Bogota", false, null, null, null, null, ai, null, null, null);

        GeminiSpeechToText stt = new GeminiSpeechToText(RestClient.builder(), props);
        List<String> models = stt.getCandidateModels();

        assertNotNull(models);
        assertFalse(models.isEmpty());

        // El primer modelo NUNCA debe ser una variante 'lite' para STT continuo
        assertFalse(models.get(0).toLowerCase().contains("lite"),
                "El modelo prioritario para audio no debe ser 'lite' para evitar cortes prematuros: " + models.get(0));

        // Debe incluir modelos robustos primero
        assertTrue(models.indexOf("gemini-3.8-flash") < models.indexOf("gemini-3.5-flash-lite"),
                "gemini-3.8-flash debe preceder a gemini-3.5-flash-lite");
    }

    @Test
    @DisplayName("extractTextFromCandidate concatena correctamente múltiples partes de respuesta de Gemini")
    void testExtractTextFromCandidateJoinsMultipleParts() {
        ObjectNode candidate = mapper.createObjectNode();
        ObjectNode content = candidate.putObject("content");
        ArrayNode parts = content.putArray("parts");

        parts.addObject().put("text", "Regálame dos pepinos, tres libras de banano, dos libras de fríjol verde,");
        parts.addObject().put("text", "cuatro libras de pulpa de mango a las 6 pm en calle 70 norte.");

        String fullText = GeminiSpeechToText.extractTextFromCandidate(candidate);

        assertEquals("Regálame dos pepinos, tres libras de banano, dos libras de fríjol verde, cuatro libras de pulpa de mango a las 6 pm en calle 70 norte.",
                fullText, "Debe concatenar todas las partes sin perder texto");
    }

    @Test
    @DisplayName("extractTextFromCandidate tolera nodos nulos o vacíos sin lanzar excepciones")
    void testExtractTextFromCandidateHandlesNullAndEmpty() {
        assertEquals("", GeminiSpeechToText.extractTextFromCandidate(null));

        ObjectNode emptyCand = mapper.createObjectNode();
        assertEquals("", GeminiSpeechToText.extractTextFromCandidate(emptyCand));

        ObjectNode noParts = mapper.createObjectNode();
        noParts.putObject("content");
        assertEquals("", GeminiSpeechToText.extractTextFromCandidate(noParts));
    }

    @Test
    @DisplayName("Debe sanitizar correctamente los tipos MIME con parámetros como codecs=opus")
    void testSanitizeMimeHandlesCodecs() {
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime("audio/ogg; codecs=opus"));
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime("audio/ogg"));
        assertEquals("audio/mp4", GeminiSpeechToText.sanitizeMime("audio/mp4; codecs=mp4a"));
        assertEquals("audio/wav", GeminiSpeechToText.sanitizeMime("audio/x-wav"));
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime(null));
        assertEquals("audio/ogg", GeminiSpeechToText.sanitizeMime("   "));
    }

    @Test
    @DisplayName("OrderBatchingCoordinator debe reportar correctamente el estado de debounce y cancelación")
    void testOrderBatchingCoordinatorDebouncingState() {
        OrderBatchingCoordinator coordinator = new OrderBatchingCoordinator(null, null, null);

        // Sin temporizador no está en debounce
        assertFalse(coordinator.isDebouncing(999L));

        coordinator.cancelTimer(999L);
        assertFalse(coordinator.isDebouncing(999L));
    }
}
