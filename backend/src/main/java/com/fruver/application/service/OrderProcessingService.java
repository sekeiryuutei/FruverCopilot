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
    private final DeliveryZoneService zones;

    public OrderProcessingService(OrderExtractor extractor, SpeechToTextService stt, AudioStorageService storage,
                                  MediaDownloader media, ProductCatalogService catalog, ExtractionInterpreter interpreter,
                                  OrderWriter writer, OrderRepository orders, QuoteService quotes,
                                  ConversationService conversation, SseHub hub, AppMetrics metrics, AppProperties props,
                                  DeliveryZoneService zones) {
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
        this.zones = zones;
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

        // 1. Si es solo un saludo sin productos previos en el pedido:
        if (!r.reply() && isGreetingOnly(text)) {
            String greeting = "¡Hola! \uD83D\uDC4B Bienvenido a FruverCopilot \uD83E\uDD51\uD83C\uDF45 "
                    + "¿En qué te podemos colaborar hoy? Puedes enviarnos tu lista de mercado (por texto o nota de voz) "
                    + "o consultarnos precios y disponibilidad de productos.";
            conversation.send(r.orderId(), greeting, ConversationService.Kind.INFO, "ia");
            writer.markInquiryHandled(r.orderId(), "Saludo inicial recibido y respondido.");
            return;
        }

        // 2. Si es una consulta directa de disponibilidad o precios de catálogo:
        if (isPriceOrInventoryInquiry(text)) {
            String reply = buildCatalogOrPriceReply(text);
            conversation.send(r.orderId(), reply, ConversationService.Kind.INFO, "ia");
            writer.markInquiryHandled(r.orderId(), "Consulta sobre disponibilidad/precios respondida automáticamente.");
            return;
        }

        ExtractionRequest req = new ExtractionRequest(text,
                writer.recentTurns(r.orderId(), r.messageId()),
                r.reply() ? writer.currentItems(r.orderId()) : java.util.List.of(),
                catalog.candidates(text),
                zones.formatForPrompt());

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
        autoActions(r.orderId(), io, text, r.audio());
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

    /** Politica configurable: auto-respuestas para pedidos y conversacion. */
    private void autoActions(long orderId, InterpretedOrder io, String text, boolean isAudio) {
        if (!props.conversation().aiAutoReply() && !props.conversation().autoConfirmClearOrders()) {
            return;
        }
        try {
            // 1. Si la IA genero una respuesta o pregunta para el cliente (customerMessage), enviala de inmediato
            if (io.customerMessage() != null && !io.customerMessage().isBlank() && io.action() == ConversationAction.ASK_CUSTOMER) {
                conversation.send(orderId, io.customerMessage(), ConversationService.Kind.ASK, "ia");
                return;
            }

            // 2. Es un pedido con productos claros (GREEN)
            // IMPORTANTE: si es AUDIO, NO auto-confirmar sin revision humana del operario
            if (io.review() == ReviewStatus.GREEN && props.conversation().autoConfirmClearOrders() && !isAudio) {
                var row = orders.find(orderId).orElse(null);
                if (row != null) {
                    if (row.address() == null || row.address().isBlank()) {
                        String askAddr = "¡Hola! \uD83D\uDC4B Ya tenemos lista tu orden. Para coordinar el despacho, ¿a qué dirección te la llevamos? 🛵";
                        conversation.send(orderId, askAddr, ConversationService.Kind.ASK, "ia");
                        return;
                    }
                    Quote q = quotes.quote(orders.items(orderId), PriceType.forCustomer(CustomerType.valueOf(row.customerType())));
                    String msg = buildOrderReceivedAndConfirmedMessage(q, row.address());
                    if (msg != null) {
                        conversation.send(orderId, msg, ConversationService.Kind.CONFIRM, "ia");
                        return;
                    }
                }
            }

            // 3. Si no esta confirmado en GREEN o requiere revision: aviso si es el primer contacto del pedido
            boolean hasPriorOutbound = conversation.messages(orderId).stream()
                    .anyMatch(m -> "OUTBOUND".equals(m.direction()));
            if (!hasPriorOutbound) {
                String ack = isAudio
                        ? "¡Hola! \uD83D\uDC4B Hemos recibido tu nota de voz y la estamos revisando. En un momento te confirmamos."
                        : "¡Hola! \uD83D\uDC4B Gracias por comunicarte con FruverCopilot. Hemos recibido tu pedido y lo estamos revisando. En un momento te confirmamos.";
                conversation.send(orderId, ack, ConversationService.Kind.INFO, "ia");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo ejecutar la respuesta automatica del pedido {}: {}", orderId, e.getMessage());
        }
    }

    private String buildOrderReceivedAndConfirmedMessage(Quote quote, String address) {
        if (quote == null || !quote.complete()) return null;
        StringBuilder sb = new StringBuilder("¡Hola! \uD83D\uDC4B Gracias por comunicarte con FruverCopilot. ");
        sb.append("Hemos recibido tu pedido y lo estamos preparando:\n\n");
        for (var l : quote.lines()) {
            sb.append("• ").append(Money.qty(l.quantity())).append(' ')
              .append(ConfirmationMessageBuilder.unitWord(l.unit(), l.quantity()))
              .append(" de ").append(l.productName()).append('\n');
        }
        sb.append("\nTotal estimado: ").append(Money.format(quote.total()));
        if (address != null && !address.isBlank()) {
            sb.append("\n📍 Dirección de entrega: ").append(address);
        }
        sb.append("\nEn un momento te confirmamos el despacho \uD83D\uDE0A");
        return sb.toString();
    }

    public boolean isPriceOrInventoryInquiry(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("me vende") || t.contains("me manda") || t.contains("enviar") || t.contains("mandeme")
                || t.contains("mándeme") || t.contains("deme") || t.contains("dame") || t.contains("quiero")
                || t.contains("llevar") || t.contains("mande") || t.contains("traigame") || t.contains("tráigame")) {
            return false;
        }
        return t.contains("precio") || t.contains("precios") || t.contains("cuanto vale") || t.contains("cuánto vale")
                || t.contains("cuanto cuesta") || t.contains("cuánto cuesta") || t.contains("a como") || t.contains("a cómo")
                || t.contains("costo") || t.contains("valor") || t.contains("catalogo") || t.contains("catálogo")
                || t.contains("inventario") || t.contains("que tienen") || t.contains("qué tienen")
                || t.contains("tienen") || t.contains("tiene") || t.contains("tienes")
                || t.contains("hay") || t.contains("vende") || t.contains("venden")
                || t.contains("maneja") || t.contains("manejan")
                || t.contains("consigue") || t.contains("consiguen")
                || t.contains("queda") || t.contains("quedan")
                || t.contains("disponible") || t.contains("disponibles") || t.contains("lista");
    }

    public boolean isGreetingOnly(String text) {
        return isGreetingOnly(text, null);
    }

    public boolean isGreetingOnly(String text, InterpretedOrder io) {
        if (io != null && io.items() != null && !io.items().isEmpty()) return false;
        if (text == null) return false;
        String t = TextTokens.fold(text).trim().replaceAll("[^a-z\\s]", "");
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
            sb.append("\n¿Deseas que te preparemos alguna cantidad? Puedes decirnos cuántos kilos o libras necesitas 😊");
        } else {
            boolean askedSpecific = tokens.stream().anyMatch(t -> !List.of("precio","precios","cuanto","cuánto","vale","cuesta","como","cómo","catalogo","catálogo","inventario","lista","tienen","tiene","tienes","hay","vende","venden","maneja","manejan","consigue","consiguen","queda","quedan","disponible","disponibles","que","qué").contains(t.toLowerCase(Locale.ROOT)));
            if (askedSpecific) {
                sb.append("¡Hola! \uD83D\uDC4B En este momento no tenemos ese producto en inventario.\n\nNuestros productos disponibles hoy son:\n\n");
            } else {
                sb.append("¡Hola! \uD83D\uDC4B En FruverCopilot tenemos disponible hoy:\n\n");
            }
            for (var p : all) {
                BigDecimal pr = p.prices().get("RETAIL");
                String fPr = pr != null ? Money.format(pr) : "precio a consultar";
                sb.append("• ").append(p.name()).append(": ").append(fPr).append(" / ").append(p.unit()).append("\n");
            }
            sb.append("\n¿Te gustaría pedir alguno de estos productos o necesitas algo más? \uD83E\uDD51\uD83C\uDF45");
        }
        return sb.toString();
    }
}
