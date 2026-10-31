package com.fruver.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiProviderCascadeAndSaturationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Gemini Key Pool salta de clave inmediatamente ante HTTP 429 de saturación")
    void geminiKeyPoolSaltaInmediatamenteAnteSaturacion429() {
        GeminiKeyPool pool = new GeminiKeyPool("AIzaSyKey11111,AIzaSyKey22222,AIzaSyKey33333", "");
        assertEquals(3, pool.size());

        // Clave 1 activa inicialmente
        GeminiKeyPool.KeyEntry k1 = pool.next();
        assertEquals(0, k1.index());
        assertEquals("AIzaSyKey11111", k1.key());

        // Se satura la clave 1 (HTTP 429)
        pool.onFailure(0, 429, Duration.ofSeconds(60));

        // La siguiente llamada debe saltar automáticamente a la Key 2 sin esperar
        GeminiKeyPool.KeyEntry k2 = pool.next();
        assertEquals(1, k2.index());
        assertEquals("AIzaSyKey22222", k2.key());
    }

    @Test
    @DisplayName("Si todas las claves de Gemini se saturan, se activa FallbackAiClient de contingencia")
    void siGeminiFallaSeActivaFallbackAiClient() {
        AppProperties.Gemini geminiProps = new AppProperties.Gemini("key1", "key1", "gemini-2.5-flash", "http://127.0.0.1:59999");
        AppProperties props = new AppProperties(
                "America/Bogota", false, null, null, null, null,
                new AppProperties.Ai("gemini", geminiProps, null, null, null, null, null), null, null, null
        );

        FallbackAiClient mockFallback = mock(FallbackAiClient.class);
        AIOrderResponse expectedFallbackResponse = new AIOrderResponse(
                List.of(new com.fruver.application.dto.ItemSugeridoDTO("5 kilos de papa", "PAPA", new java.math.BigDecimal("5"), "KG", false, null)),
                com.fruver.domain.enums.ConversationAction.CONFIRM_ORDER, "OK", "Extracción desde Fallback Groq", null, null, null, null
        );
        when(mockFallback.tryFallback(any(), any())).thenReturn(expectedFallbackResponse);

        RestClient.Builder restBuilder = RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server = org.springframework.test.web.client.MockRestServiceServer.bindTo(restBuilder).ignoreExpectOrder(true).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        GeminiOrderExtractor extractor = new GeminiOrderExtractor(restBuilder, props, mapper, mockFallback);

        ExtractionRequest request = new ExtractionRequest(
                "5 kilos de papa", List.of(), List.of(), List.of(), "Zona 1", null
        );

        // Al no haber endpoint real de Gemini, debe caer al mockFallback
        AIOrderResponse response = extractor.extract(request);
        assertNotNull(response);
        assertEquals("Extracción desde Fallback Groq", response.clarificationReason());
        assertEquals(1, response.items().size());
        assertEquals("PAPA", response.items().get(0).productoNormalizado());
    }

    @Test
    @DisplayName("Si TODAS las IAs fallan (apagón total), arroja ExtractionUnavailableException controlada sin perder datos")
    void siTodasLasIasFallanArrojaExtractionUnavailableException() {
        AppProperties.Gemini geminiProps = new AppProperties.Gemini("key1", "key1", "gemini-2.5-flash", "http://127.0.0.1:59999");
        AppProperties props = new AppProperties(
                "America/Bogota", false, null, null, null, null,
                new AppProperties.Ai("gemini", geminiProps, null, null, null, null, null), null, null, null
        );

        FallbackAiClient mockFallback = mock(FallbackAiClient.class);
        // Fallback también falla devolviendo null
        when(mockFallback.tryFallback(any(), any())).thenReturn(null);

        RestClient.Builder restBuilder = RestClient.builder();
        org.springframework.test.web.client.MockRestServiceServer server = org.springframework.test.web.client.MockRestServiceServer.bindTo(restBuilder).ignoreExpectOrder(true).build();
        server.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        GeminiOrderExtractor extractor = new GeminiOrderExtractor(restBuilder, props, mapper, mockFallback);

        ExtractionRequest request = new ExtractionRequest(
                "10 de tomate", List.of(), List.of(), List.of(), "Zona 1", null
        );

        ExtractionUnavailableException ex = assertThrows(ExtractionUnavailableException.class, () -> extractor.extract(request));
        assertTrue(ex.getMessage().contains("Fallo la extracción") || ex.getMessage().contains("Gemini"));
    }
}

