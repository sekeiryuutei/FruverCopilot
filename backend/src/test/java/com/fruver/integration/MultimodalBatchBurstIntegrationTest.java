package com.fruver.integration;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.OrderExtractor;
import com.fruver.application.port.SpeechToTextService;
import com.fruver.application.port.ThermalPrinterService;
import com.fruver.application.service.MessageIngestService;
import com.fruver.application.service.OrderProcessingService;
import com.fruver.application.service.OrderBatchingCoordinator;
import com.fruver.domain.enums.ConversationAction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "app.security.admin-password=test-only-password",
        "app.audio.storage-path=target/test-audio",
        "app.printing.poll-ms=3600000",
        "app.printing.auto-print-wholesale=false",
        "app.conversation.ai-auto-reply=false",
        "app.conversation.auto-confirm-clear-orders=false",
        "app.conversation.debounce-seconds=0" // 0 para permitir sincronización determinística en prueba
})
@Testcontainers(disabledWithoutDocker = true)
class MultimodalBatchBurstIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean OrderExtractor extractor;
    @MockitoBean SpeechToTextService stt;
    @MockitoBean ThermalPrinterService printer;

    @Autowired MessageIngestService ingest;
    @Autowired OrderProcessingService processing;
    @Autowired JdbcClient jdbc;

    @Test
    void rafagaCompletaAudioFotoTextoFotoSeConsolidaEnUnSoloTokenBatch() {
        String phone = "573118889900";
        String customerName = "Restaurante Central";

        // Simular respuesta de STT para el audio
        when(stt.transcribe(any(), any())).thenReturn("Buenas tardes, mándeme 4 libras de papa criolla");

        // Simular respuesta de la IA cuando recibe todo el lote consolidado
        AIOrderResponse aiResponse = new AIOrderResponse(
                List.of(
                        new ItemSugeridoDTO("4 lb papa criolla", "Papa Criolla", new BigDecimal("4"), "LB", false, null),
                        new ItemSugeridoDTO("3 bultos papa pastusa", "Papa Pastusa", new BigDecimal("3"), "BULTO", false, null),
                        new ItemSugeridoDTO("2 cajas tomate", "Tomate Chonto", new BigDecimal("2"), "CAJA", false, null),
                        new ItemSugeridoDTO("5 atados cilantro", "Cilantro", new BigDecimal("5"), "UNIDAD", false, null)
                ),
                ConversationAction.CONFIRM_ORDER,
                null,
                "Lote multimodal consolidado (audio + 2 fotos + texto)"
        );

        when(extractor.extractBatch(any(), any(), any())).thenReturn(aiResponse);
        when(extractor.extractImage(any(), any(), any())).thenReturn(aiResponse);
        when(extractor.extract(any())).thenReturn(aiResponse);

        // 1. Cliente envía Audio
        byte[] fakeAudio = new byte[]{ 0x11, 0x22, 0x33, 0x44 };
        ingest.ingestAudioBytes(phone, customerName, "aud-" + UUID.randomUUID(), fakeAudio, "audio/ogg");

        // 2. Cliente envía Foto 1
        byte[] fakeImg1 = new byte[]{ (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01 };
        ingest.ingestImageBytes(phone, customerName, "img-" + UUID.randomUUID(), fakeImg1, "image/jpeg", "Foto lista de papa");

        // 3. Cliente envía Texto
        ingest.ingestText(phone, customerName, "txt-" + UUID.randomUUID(), "Y también 2 cajas de tomate chonto");

        // 4. Cliente envía Foto 2
        byte[] fakeImg2 = new byte[]{ (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x02 };
        ingest.ingestImageBytes(phone, customerName, "img-" + UUID.randomUUID(), fakeImg2, "image/jpeg", "Foto verduras");

        // Verificar que los 4 mensajes quedaron asociados AL MISMO PEDIDO (un solo order_id)
        Long orderId = jdbc.sql("SELECT o.id FROM orders o JOIN customer c ON c.id = o.customer_id WHERE c.phone = :p ORDER BY o.id DESC LIMIT 1")
                .param("p", phone).query(Long.class).single();

        assertNotNull(orderId);

        // Esperar a que el procesamiento asíncrono consolide y aplique la extracción
        await().atMost(10, TimeUnit.SECONDS).until(() -> {
            Integer itemCount = jdbc.sql("SELECT count(*) FROM order_item WHERE order_id = :id")
                    .param("id", orderId).query(Integer.class).single();
            return itemCount != null && itemCount >= 4;
        });

        // Verificar que los 4 mensajes existen en la conversación del pedido
        Integer msgCount = jdbc.sql("SELECT count(*) FROM conversation_message WHERE order_id = :id AND direction = 'INBOUND'")
                .param("id", orderId).query(Integer.class).single();
        assertEquals(4, msgCount, "Deben estar guardados los 4 mensajes en la base de datos");

        // Verificar que hay 1 audio y 2 fotos registradas en sus tablas
        Integer audioCount = jdbc.sql("SELECT count(*) FROM audio WHERE order_id = :id").param("id", orderId).query(Integer.class).single();
        Integer imgCount = jdbc.sql("SELECT count(*) FROM media_image WHERE order_id = :id").param("id", orderId).query(Integer.class).single();
        assertEquals(1, audioCount, "Debe haber 1 registro de audio");
        assertEquals(2, imgCount, "Debe haber 2 registros de fotos");

        // Verificar que extractor.extractBatch fue invocado con las 2 imágenes juntas en una sola llamada
        ArgumentCaptor<List<byte[]>> imgCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ExtractionRequest> reqCaptor = ArgumentCaptor.forClass(ExtractionRequest.class);

        verify(extractor, atLeastOnce()).extractBatch(imgCaptor.capture(), any(), reqCaptor.capture());

        List<byte[]> capturedImages = imgCaptor.getValue();
        assertEquals(2, capturedImages.size(), "Ambas fotos deben enviarse juntas en el mismo token batch");

        ExtractionRequest lastReq = reqCaptor.getValue();
        assertNotNull(lastReq.message());
        // Debe contener el texto del audio transcrito y el texto del cliente
        assertTrue(lastReq.message().contains("papa criolla") || lastReq.message().contains("tomate chonto"));

        // El pedido en la BD debe contener los 4 productos sin duplicados
        List<String> items = jdbc.sql("SELECT mentioned FROM order_item WHERE order_id = :id").param("id", orderId).query(String.class).list();
        assertEquals(4, items.size());
    }
}

