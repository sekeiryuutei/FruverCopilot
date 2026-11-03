package com.fruver.integration;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.OrderExtractor;
import com.fruver.application.service.ConversationService;
import com.fruver.application.service.MessageIngestService;
import com.fruver.domain.enums.ConversationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regla de negocio: un mensaje entrante NUNCA debe producir un mensaje de texto saliente.
 * La unica salida permitida es el emoji de confirmacion al imprimir, que no pasa por este flujo.
 *
 * Se mockea ConversationService para detectar cualquier intento de respuesta automatica sin
 * enviar nada a WhatsApp ni depender de un proveedor externo.
 */
@SpringBootTest(properties = {
        "app.security.admin-password=test-only-password",
        "app.audio.storage-path=target/test-audio",
        "app.printing.poll-ms=3600000",
        "app.printing.auto-print-wholesale=false",
        "app.conversation.ai-auto-reply=false",
        "app.conversation.auto-confirm-clear-orders=false",
        "app.conversation.debounce-seconds=0",
        "app.whatsapp.enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class InboundMessageNoOutboundTextIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean OrderExtractor extractor;
    @MockitoBean ConversationService conversation;

    @Autowired MessageIngestService ingest;
    @Autowired JdbcClient jdbc;

    private static final AtomicLong PHONE_SEQUENCE = new AtomicLong(10_000_000L);

    @BeforeEach
    void clearConversationInvocations() {
        clearInvocations(conversation);
    }

    @ParameterizedTest(name = "consulta sin pedido no responde: {0}")
    @ValueSource(strings = {
            "hola",
            "gracias",
            "ya le transferi por nequi",
            "cuanto vale la papa"
    })
    void inquiryDoesNotProduceOutboundText(String incomingText) {
        long orderId = ingestAndAwaitCancelled(incomingText);

        assertNoOutboundText(orderId);
    }

    @Test
    void clearOrderDoesNotProduceOutboundText() {
        when(extractor.extract(any())).thenReturn(new AIOrderResponse(
                List.of(new ItemSugeridoDTO("2 libras de papa criolla", "Papa criolla",
                        new BigDecimal("2"), "LB", false, null)),
                ConversationAction.CONFIRM_ORDER, null, null));

        long orderId = ingestAndAwaitStatus("2 libras de papa criolla", "REVIEW");

        assertNoOutboundText(orderId);
    }

    @Test
    void productPhotoRequestDoesNotProduceOutboundText() {
        long orderId = ingestAndAwaitCancelled("me manda una foto del tomate");

        assertNoOutboundText(orderId);
    }

    private long ingestAndAwaitCancelled(String text) {
        return ingestAndAwaitStatus(text, "CANCELLED");
    }

    private long ingestAndAwaitStatus(String text, String expectedStatus) {
        String phone = "573" + PHONE_SEQUENCE.getAndIncrement();
        String externalId = "no-outbound-" + UUID.randomUUID();

        ingest.ingestText(phone, "Cliente de prueba", externalId, text);

        await().atMost(10, TimeUnit.SECONDS).until(() ->
                expectedStatus.equals(latestOrderStatus(phone))
        );

        return jdbc.sql("SELECT id FROM orders WHERE customer_phone = :p ORDER BY id DESC LIMIT 1")
                .param("p", phone)
                .query(Long.class)
                .single();
    }

    private String latestOrderStatus(String phone) {
        return jdbc.sql("SELECT status FROM orders WHERE customer_phone = :p ORDER BY id DESC LIMIT 1")
                .param("p", phone)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private void assertNoOutboundText(long orderId) {
        verify(conversation, never()).send(anyLong(), anyString(), any(ConversationService.Kind.class), anyString());
        verify(conversation, never()).sendImage(anyLong(), anyString(), anyString(), anyString());

        int outboundMessages = jdbc.sql("""
                SELECT count(*) FROM conversation_message
                WHERE order_id = :o AND direction = 'OUTBOUND'
                """).param("o", orderId).query(Integer.class).single();
        assertEquals(0, outboundMessages, "El mensaje entrante no debe generar texto saliente");
    }
}
