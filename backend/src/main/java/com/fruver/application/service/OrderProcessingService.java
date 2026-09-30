package com.fruver.application.service;

import com.fruver.application.dto.*;
import com.fruver.application.port.*;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.CustomerType;
import com.fruver.domain.enums.PriceType;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.infrastructure.persistence.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Pipeline asincrono: audio -> STT -> contexto -> LLM -> validacion -> persistencia -> acciones.
 * NINGUNA llamada externa (STT, LLM, WhatsApp) ocurre dentro de una transaccion de base de datos.
 * Si algo tecnico falla, el pedido NO desaparece: queda en revision con estado de IA fallida.
 */
@Service
public class OrderProcessingService {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessingService.class);

    private final OrderExtractor extractor;
    private final SpeechToTextService stt;
    private final AudioStorageService storage;
    private final MediaDownloader media;
    private final ProductCatalogService catalog;
    private final ExtractionInterpreter interpreter;
    private final OrderWriter writer;
    private final OrderRepository orders;
    private final QuoteService quotes;
    private final ConversationService conversation;
    private final SseHub hub;
    private final AppMetrics metrics;
    private final AppProperties props;

    public OrderProcessingService(OrderExtractor extractor, SpeechToTextService stt, AudioStorageService storage,
                                  MediaDownloader media, ProductCatalogService catalog, ExtractionInterpreter interpreter,
                                  OrderWriter writer, OrderRepository orders, QuoteService quotes,
                                  ConversationService conversation, SseHub hub, AppMetrics metrics, AppProperties props) {
        this.extractor = extractor;
        this.stt = stt;
        this.storage = storage;
        this.media = media;
        this.catalog = catalog;
        this.interpreter = interpreter;
        this.writer = writer;
        this.orders = orders;
        this.quotes = quotes;
        this.conversation = conversation;
        this.hub = hub;
        this.metrics = metrics;
        this.props = props;
    }

    @Async("aiExecutor")
    public void process(IntakeResult r) {
        try {
            doProcess(r);
        } catch (Exception e) {
            log.error("Fallo inesperado procesando pedido {}", r.orderId(), e);
            metrics.aiError("unexpected");
            writer.markFailed(r.orderId(), "FAILED", "Error inesperado; digitar manualmente");
        }
        hub.orderChanged(r.orderId());
    }

    private void doProcess(IntakeResult r) {
        String text = r.text();
        if (r.audio()) {
            text = transcribe(r);
            if (text == null) return;
        }

        ExtractionRequest req = new ExtractionRequest(text,
                writer.recentTurns(r.orderId(), r.messageId()),
                r.reply() ? writer.currentItems(r.orderId()) : java.util.List.of(),
                catalog.candidates(text));

        long t0 = System.nanoTime();
        AIOrderResponse ai;
        try {
            ai = extractor.extract(req);
        } catch (ExtractionUnavailableException e) {
            log.warn("IA no disponible para pedido {}: {}", r.orderId(), e.getMessage());
            metrics.aiError("unavailable");
            writer.markFailed(r.orderId(), "FAILED", "IA no disponible; digitar el pedido manualmente");
            return;
        } finally {
            metrics.extractionTime(Duration.ofNanos(System.nanoTime() - t0));
        }

        InterpretedOrder io = interpreter.interpret(ai);
        if (!writer.applyExtraction(r.orderId(), io)) return;
        metrics.review(io.review().name());
        autoActions(r.orderId(), io);
    }

    /** @return texto transcrito, o null si fallo (el pedido queda en rojo con el audio disponible). */
    private String transcribe(IntakeResult r) {
        try {
            String key = r.storageKey();
            byte[] data;
            String mime = r.mime();
            if (key == null) {
                MediaDownloader.MediaFile f = media.download(r.mediaId());
                mime = f.mime();
                key = storage.save(f.data(), mime);
                writer.attachAudio(r.messageId(), key);
                data = f.data();
            } else {
                data = storage.load(key);
            }
            String text = stt.transcribe(data, mime);
            if (text == null || text.isBlank()) {
                metrics.aiError("empty_transcription");
                writer.markFailed(r.orderId(), "TRANSCRIPTION_FAILED", "La transcripcion salio vacia; escuchar el audio");
                return null;
            }
            writer.saveTranscription(r.messageId(), text.strip(), props.stt().model());
            return text.strip();
        } catch (RuntimeException e) {
            log.warn("Transcripcion fallida para pedido {}: {}", r.orderId(), e.getMessage());
            metrics.aiError("transcription");
            writer.markFailed(r.orderId(), "TRANSCRIPTION_FAILED", "No se pudo transcribir; escuchar el audio");
            return null;
        }
    }

    /** Politica configurable: por defecto la IA NO escribe al cliente sin que un empleado lo apruebe. */
    private void autoActions(long orderId, InterpretedOrder io) {
        try {
            if (io.action() == ConversationAction.ASK_CUSTOMER && props.conversation().aiAutoReply()
                    && io.customerMessage() != null) {
                conversation.send(orderId, io.customerMessage(), ConversationService.Kind.ASK, "ia");
            } else if (io.review() == ReviewStatus.GREEN && props.conversation().autoConfirmClearOrders()) {
                var row = orders.find(orderId).orElse(null);
                if (row == null) return;
                Quote q = quotes.quote(orders.items(orderId), PriceType.forCustomer(CustomerType.valueOf(row.customerType())));
                String msg = ConfirmationMessageBuilder.build(q);
                if (msg != null) conversation.send(orderId, msg, ConversationService.Kind.CONFIRM, "ia");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo ejecutar la respuesta automatica del pedido {}: {}", orderId, e.getMessage());
        }
    }
}
