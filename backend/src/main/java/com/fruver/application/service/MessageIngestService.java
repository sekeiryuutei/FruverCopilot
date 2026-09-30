package com.fruver.application.service;

import com.fruver.application.dto.IntakeResult;
import com.fruver.application.port.AudioStorageService;
import com.fruver.domain.enums.OrderInputType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Punto de entrada de mensajes (WhatsApp, simulador). No es transaccional a proposito:
 * 1) registra en una transaccion corta, 2) avisa a la pantalla, 3) procesa la IA de forma asincrona.
 */
@Service
public class MessageIngestService {

    private static final Logger log = LoggerFactory.getLogger(MessageIngestService.class);

    private final OrderIntakeService intake;
    private final OrderProcessingService processing;
    private final AudioStorageService storage;
    private final SseHub hub;
    private final AppMetrics metrics;

    public MessageIngestService(OrderIntakeService intake, OrderProcessingService processing,
                                AudioStorageService storage, SseHub hub, AppMetrics metrics) {
        this.intake = intake;
        this.processing = processing;
        this.storage = storage;
        this.hub = hub;
        this.metrics = metrics;
    }

    public void ingestText(String phone, String name, String externalId, String text) {
        ingest(phone, name, externalId, OrderInputType.TEXT, text, null, null, null);
    }

    /** Audio referenciado en el proveedor (se descarga de forma asincrona: las URLs de media expiran). */
    public void ingestAudioRef(String phone, String name, String externalId, String mediaId, String mime) {
        ingest(phone, name, externalId, OrderInputType.AUDIO, null, mediaId, null, mime);
    }

    /** Audio ya disponible en bytes (simulador / pruebas). */
    public void ingestAudioBytes(String phone, String name, String externalId, byte[] data, String mime) {
        if (externalId != null && intake.isDuplicate(externalId)) return;
        ingest(phone, name, externalId, OrderInputType.AUDIO, null, null, storage.save(data, mime), mime);
    }

    private void ingest(String phone, String name, String externalId, OrderInputType type, String text,
                        String mediaId, String storageKey, String mime) {
        if (phone == null || phone.isBlank()) throw new ApiException(400, "VALIDATION_ERROR", "Telefono requerido");
        if (externalId != null && intake.isDuplicate(externalId)) {
            log.debug("Mensaje duplicado ignorado");
            return;
        }
        IntakeResult result;
        try {
            result = intake.record(phone.strip(), name, externalId, type, text, mediaId, storageKey, mime);
        } catch (DuplicateKeyException e) {
            log.debug("Mensaje duplicado (carrera) ignorado");
            return;
        }
        metrics.orderReceived(type.name());
        hub.orderChanged(result.orderId());
        processing.process(result);
    }
}
