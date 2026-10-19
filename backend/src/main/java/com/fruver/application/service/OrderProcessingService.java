package com.fruver.application.service;

import com.fruver.application.dto.*;
import com.fruver.application.port.*;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.CustomerType;
import com.fruver.domain.enums.PriceType;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.infrastructure.persistence.OrderRepository;
import com.fruver.domain.service.Money;
import com.fruver.domain.service.TextTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        autoActions(r.orderId(), io, text);
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

    /** Politica configurable: auto-respuestas para saludos, consultas de catalogo/precios y pedidos. */
    private void autoActions(long orderId, InterpretedOrder io, String text) {
        if (!props.conversation().aiAutoReply() && !props.conversation().autoConfirmClearOrders()) {
            return;
        }
        try {
            // 1. ¿Es consulta de precios o inventario?
            if (isPriceOrInventoryInquiry(text)) {
                String reply = buildCatalogOrPriceReply(text);
                conversation.send(orderId, reply, ConversationService.Kind.INFO, "ia");
                return;
            }

            // 2. ¿Es solo un saludo sin productos?
            if (isGreetingOnly(text, io)) {
                String greeting = "¡Hola! \uD83D\uDC4B Bienvenido a FruverCopilot \uD83E\uDD51\uD83C\uDF45 "
                        + "¿En qué te podemos colaborar hoy? Puedes enviarnos tu lista de mercado (por texto o nota de voz) "
                        + "o consultarnos precios y disponibilidad de productos.";
                conversation.send(orderId, greeting, ConversationService.Kind.INFO, "ia");
                return;
            }

            // 3. Es un pedido con productos
            if (io.review() == ReviewStatus.GREEN && props.conversation().autoConfirmClearOrders()) {
                var row = orders.find(orderId).orElse(null);
                if (row != null) {
                    Quote q = quotes.quote(orders.items(orderId), PriceType.forCustomer(CustomerType.valueOf(row.customerType())));
                    String msg = buildOrderReceivedAndConfirmedMessage(q);
                    if (msg != null) {
                        conversation.send(orderId, msg, ConversationService.Kind.CONFIRM, "ia");
                        return;
                    }
                }
            }

            // Si no está confirmado en GREEN o requiere revisión:
            // Responde avisando que estamos revisando su pedido (solo si es el primer contacto del pedido)
            boolean hasPriorOutbound = conversation.messages(orderId).stream()
                    .anyMatch(m -> "OUTBOUND".equals(m.direction()));
            if (!hasPriorOutbound) {
                String ack = "¡Hola! \uD83D\uDC4B Gracias por comunicarte con FruverCopilot. "
                        + "Hemos recibido tu pedido y lo estamos revisando. En un momento te confirmamos.";
                conversation.send(orderId, ack, ConversationService.Kind.INFO, "ia");
            }

            // Si hay una pregunta de aclaración específica (ASK_CUSTOMER), envíala
            if (io.action() == ConversationAction.ASK_CUSTOMER && io.customerMessage() != null) {
                conversation.send(orderId, io.customerMessage(), ConversationService.Kind.ASK, "ia");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo ejecutar la respuesta automatica del pedido {}: {}", orderId, e.getMessage());
        }
    }

    private String buildOrderReceivedAndConfirmedMessage(Quote quote) {
        if (quote == null || !quote.complete()) return null;
        StringBuilder sb = new StringBuilder("¡Hola! \uD83D\uDC4B Gracias por comunicarte con FruverCopilot. ");
        sb.append("Hemos recibido tu pedido y lo estamos preparando:\n\n");
        for (var l : quote.lines()) {
            sb.append("• ").append(Money.qty(l.quantity())).append(' ')
              .append(ConfirmationMessageBuilder.unitWord(l.unit(), l.quantity()))
              .append(" de ").append(l.productName()).append('\n');
        }
        sb.append("\nTotal estimado: ").append(Money.format(quote.total()))
          .append("\nEn un momento te confirmamos el despacho \uD83D\uDE0A");
        return sb.toString();
    }

    boolean isPriceOrInventoryInquiry(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("me vende") || t.contains("me manda") || t.contains("enviar") || t.contains("mandeme") || t.contains("mándeme")) {
            return false;
        }
        return t.contains("precio") || t.contains("precios") || t.contains("cuanto vale") || t.contains("cuánto vale")
                || t.contains("cuanto cuesta") || t.contains("cuánto cuesta") || t.contains("a como") || t.contains("a cómo")
                || t.contains("costo") || t.contains("valor") || t.contains("catalogo") || t.contains("catálogo")
                || t.contains("inventario") || t.contains("que tienen") || t.contains("qué tienen")
                || t.contains("tienen") || t.contains("disponible") || t.contains("disponibles") || t.contains("lista");
    }

    boolean isGreetingOnly(String text, InterpretedOrder io) {
        if (io != null && io.items() != null && !io.items().isEmpty()) return false;
        if (text == null) return false;
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-záéíóúñ\\s]", "");
        return t.matches("^(hola|buenas|buenos dias|buenas tardes|buenas noches|buen dia|que tal|hola buenas|hola buenos dias|hola buenas tardes|hola buenas noches)$");
    }

    String buildCatalogOrPriceReply(String text) {
        var all = catalog.list("", false);
        List<String> tokens = TextTokens.significant(text);
        List<ProductCatalogService.ProductView> matches = new ArrayList<>();
        for (var p : all) {
            String pName = p.name().toLowerCase(Locale.ROOT);
            for (String tok : tokens) {
                if (tok.length() >= 3 && pName.contains(tok.toLowerCase(Locale.ROOT))) {
                    if (!matches.contains(p)) matches.add(p);
                    break;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (!matches.isEmpty()) {
            sb.append("¡Hola! \uD83D\uDC4B Sí tenemos disponible:\n\n");
            for (var p : matches) {
                BigDecimal pr = p.prices().get("RETAIL");
                String fPr = pr != null ? Money.format(pr) : "precio a consultar";
                sb.append("• ").append(p.name()).append(": ").append(fPr).append(" / ").append(p.unit()).append("\n");
            }
            sb.append("\n¿Deseas que te preparemos alguna cantidad? Puedes enviarnos un texto o audio \uD83D\uDE0A");
        } else {
            boolean askedSpecific = tokens.stream().anyMatch(t -> !List.of("precio","precios","cuanto","cuánto","vale","cuesta","como","cómo","catalogo","catálogo","inventario","lista","tienen","hay","que","qué").contains(t.toLowerCase(Locale.ROOT)));
            if (askedSpecific && tokens.size() > 2) {
                sb.append("¡Hola! \uD83D\uDC4B En este momento no tenemos ese producto específico en inventario.\n\nNuestros productos disponibles hoy son:\n");
            } else {
                sb.append("¡Hola! \uD83D\uDC4B En FruverCopilot tenemos disponible hoy:\n\n");
            }
            for (var p : all) {
                BigDecimal pr = p.prices().get("RETAIL");
                String fPr = pr != null ? Money.format(pr) : "precio a consultar";
                sb.append("• ").append(p.name()).append(": ").append(fPr).append(" / ").append(p.unit()).append("\n");
            }
            sb.append("\n¡Puedes enviarnos tu pedido por aquí en texto o nota de voz cuando desees! \uD83E\uDD51\uD83C\uDF45");
        }
        return sb.toString();
    }
}
