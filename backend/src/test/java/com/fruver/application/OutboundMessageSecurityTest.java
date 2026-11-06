package com.fruver.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.port.AudioStorageService;
import com.fruver.application.port.MessagingGateway;
import com.fruver.application.service.*;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.OrderInputType;
import com.fruver.interfaces.rest.WhatsAppWebhookController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite de pruebas de seguridad para mensajes salientes (OUTBOUND):
 * Garantiza que NINGÚN mensaje enviado desde el celular físico (dispositivo)
 * ni desde la aplicación web cree pedidos activos, altere estados de facturación
 * ni dispare extracción de IA.
 */
class OutboundMessageSecurityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AppProperties createProps() {
        AppProperties.Whatsapp wa = new AppProperties.Whatsapp(true, "token-xyz", "ignore", "token", "phone-id", "v21.0", false, false, false, false);
        return new AppProperties("America/Bogota", false, null, null, null, wa, null, null, null, null);
    }

    @Test
    @DisplayName("Mensaje desde celular físico (from_me=true) NO crea pedidos de texto ni llama ingestText")
    void webhookFromDeviceDoesNotTriggerIngestText() {
        AppProperties props = createProps();

        AtomicBoolean orderIngested = new AtomicBoolean(false);
        AtomicBoolean outboundIngested = new AtomicBoolean(false);

        MessageIngestService ingest = new MessageIngestService(null, null, null, null, null) {
            @Override
            public void ingestText(String from, String name, String id, String text, String channel) {
                orderIngested.set(true);
            }

            @Override
            public void ingestOutbound(String phone, String externalId, String text, String messageType,
                                       String mediaId, String mime, String channel) {
                outboundIngested.set(true);
                assertEquals("573156793777", phone);
                assertEquals("msg_from_phone_123", externalId);
                assertEquals("Hola, ya te despacho 2 kilos de papa", text);
                assertEquals("text", messageType);
            }
        };

        WhatsAppWebhookController controller = new WhatsAppWebhookController(props, ingest, mapper);

        // Payload simulando WhatsApp Web / Baileys con from_me = true
        String jsonFromDevice = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "contacts": [{"wa_id": "573156793777", "profile": {"name": "Cliente Don Pedro"}}],
                        "messages": [{
                          "id": "msg_from_phone_123",
                          "from": "573156793777",
                          "from_me": true,
                          "type": "text",
                          "text": {"body": "Hola, ya te despacho 2 kilos de papa"}
                        }]
                      }
                    }]
                  }]
                }
                """;

        MockHttpServletRequest req = new MockHttpServletRequest();
        var res = controller.receive(jsonFromDevice, null, req);

        assertEquals(200, res.getStatusCode().value());
        assertTrue(outboundIngested.get(), "El mensaje saliente debe registrarse como outbound");
        assertFalse(orderIngested.get(), "¡CRÍTICO! Un mensaje con from_me=true NUNCA debe invocar ingestText ni crear pedidos");
    }

    @Test
    @DisplayName("Audio o Imagen desde celular físico (from_me=true) NO llama ingestAudioRef ni ingestImageRef")
    void webhookAudioOrImageFromDeviceDoesNotTriggerOrderIngest() {
        AppProperties props = createProps();

        AtomicBoolean audioOrderIngested = new AtomicBoolean(false);
        AtomicBoolean outboundIngested = new AtomicBoolean(false);

        MessageIngestService ingest = new MessageIngestService(null, null, null, null, null) {
            @Override
            public void ingestAudioRef(String phone, String name, String externalId, String mediaId, String mime, String channel) {
                audioOrderIngested.set(true);
            }

            @Override
            public void ingestOutbound(String phone, String externalId, String text, String messageType,
                                       String mediaId, String mime, String channel) {
                outboundIngested.set(true);
                assertEquals("audio", messageType);
                assertEquals("audio_id_999", mediaId);
            }
        };

        WhatsAppWebhookController controller = new WhatsAppWebhookController(props, ingest, mapper);

        String jsonAudio = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "contacts": [{"wa_id": "573156793777", "profile": {"name": "Cliente"}}],
                        "messages": [{
                          "id": "audio_msg_456",
                          "from": "573156793777",
                          "from_me": true,
                          "type": "audio",
                          "audio": {"id": "audio_id_999", "mime_type": "audio/ogg"}
                        }]
                      }
                    }]
                  }]
                }
                """;

        var res = controller.receive(jsonAudio, null, new MockHttpServletRequest());
        assertEquals(200, res.getStatusCode().value());
        assertTrue(outboundIngested.get());
        assertFalse(audioOrderIngested.get(), "¡CRÍTICO! Un audio enviado desde el celular NO debe procesarse como pedido entrante");
    }

    @Test
    @DisplayName("Mensaje entrante real de cliente (from_me=false) SÍ se procesa como pedido normal")
    void normalCustomerMessageStillTriggersIngestText() {
        AppProperties props = createProps();

        AtomicBoolean orderIngested = new AtomicBoolean(false);

        MessageIngestService ingest = new MessageIngestService(null, null, null, null, null) {
            @Override
            public void ingestText(String from, String name, String id, String text, String channel) {
                orderIngested.set(true);
            }
        };

        WhatsAppWebhookController controller = new WhatsAppWebhookController(props, ingest, mapper);

        String jsonCustomer = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "contacts": [{"wa_id": "573156793777", "profile": {"name": "Cliente"}}],
                        "messages": [{
                          "id": "cust_msg_789",
                          "from": "573156793777",
                          "from_me": false,
                          "type": "text",
                          "text": {"body": "Necesito 3 piñas"}
                        }]
                      }
                    }]
                  }]
                }
                """;

        controller.receive(jsonCustomer, null, new MockHttpServletRequest());
        assertTrue(orderIngested.get(), "Mensajes de clientes (from_me=false) deben procesarse normalmente");
    }

    @Test
    @DisplayName("MessageIngestService.ingestOutbound NUNCA llama a OrderProcessingService ni OrderBatchingCoordinator")
    void ingestOutboundNeverCallsAiProcessingOrBatching() {
        AtomicBoolean aiProcessed = new AtomicBoolean(false);
        AtomicBoolean batchScheduled = new AtomicBoolean(false);
        AtomicInteger metricsIncremented = new AtomicInteger(0);
        AtomicBoolean sseNotified = new AtomicBoolean(false);
        AtomicBoolean recordOutboundCalled = new AtomicBoolean(false);

        OrderIntakeService mockIntake = new OrderIntakeService(null, null) {
            @Override
            public boolean isDuplicate(String externalId) {
                return false;
            }

            @Override
            public Long recordOutbound(String phone, String externalId, String text, String messageType,
                                       String mediaId, String storageKey, String mime, String channel) {
                recordOutboundCalled.set(true);
                return 555L; // retorna orderId asociado
            }
        };

        OrderProcessingService mockProcessing = new OrderProcessingService(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null) {
            @Override
            public void process(com.fruver.application.dto.IntakeResult intake) {
                aiProcessed.set(true);
            }
        };

        OrderBatchingCoordinator mockCoordinator = new OrderBatchingCoordinator(null, null, null) {
            @Override
            public void scheduleProcessing(com.fruver.application.dto.IntakeResult intake) {
                batchScheduled.set(true);
            }
        };

        SseHub mockHub = new SseHub() {
            @Override
            public void orderChanged(long orderId) {
                sseNotified.set(true);
            }
        };

        AppMetrics mockMetrics = new AppMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()) {
            @Override
            public void orderReceived(String inputType) {
                metricsIncremented.incrementAndGet();
            }
        };

        MessageIngestService ingestService = new MessageIngestService(
                mockIntake, mockProcessing, null, mockHub, mockMetrics, mockCoordinator
        );

        ingestService.ingestOutbound("573156793777", "ext_999", "Mensaje enviado desde WhatsApp del cel", "TEXT", null, null, "BAILEYS_QR");

        assertTrue(recordOutboundCalled.get(), "Debe llamar a recordOutbound");
        assertTrue(sseNotified.get(), "Debe notificar a la pantalla por SSE para reflejar el chat");
        assertFalse(aiProcessed.get(), "¡CRÍTICO! No debe ejecutar procesamiento con IA");
        assertFalse(batchScheduled.get(), "¡CRÍTICO! No debe encolar debounce de lote");
        assertEquals(0, metricsIncremented.get(), "No debe contar como nueva orden recibida");
    }

    @Test
    @DisplayName("Mensaje saliente duplicado es descartado silenciosamente sin llamar recordOutbound")
    void duplicateOutboundMessageIsIgnored() {
        AtomicBoolean recordCalled = new AtomicBoolean(false);

        OrderIntakeService mockIntake = new OrderIntakeService(null, null) {
            @Override
            public boolean isDuplicate(String externalId) {
                return "duplicate_ext_id".equals(externalId);
            }

            @Override
            public Long recordOutbound(String phone, String externalId, String text, String messageType,
                                       String mediaId, String storageKey, String mime, String channel) {
                recordCalled.set(true);
                return null;
            }
        };

        MessageIngestService ingestService = new MessageIngestService(mockIntake, null, null, new SseHub() {}, null, null);

        ingestService.ingestOutbound("573156793777", "duplicate_ext_id", "Texto ya registrado", "TEXT", null, null, "BAILEYS_QR");
        assertFalse(recordCalled.get(), "Si el mensaje ya existe en BD, no debe volver a registrarse");
    }

    @Test
    @DisplayName("ChatQueryService.sendManualMessage valida longitud y evita mensajes vacíos")
    void sendManualMessageValidatesInputs() {
        ChatQueryService chatService = new ChatQueryService(null, null, null, null, null);

        assertThrows(ApiException.class, () ->
                chatService.sendManualMessage("573156793777", "", "operador"));

        assertThrows(ApiException.class, () ->
                chatService.sendManualMessage("573156793777", null, "operador"));

        String tooLong = "A".repeat(2001);
        assertThrows(ApiException.class, () ->
                chatService.sendManualMessage("573156793777", tooLong, "operador"));
    }
}
