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
import java.util.regex.Pattern;
import com.fruver.infrastructure.persistence.Rows.OrderRow;

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
    private final SaleService sales;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;
    private final PrinterConfigService printerConfig;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderProcessingService(OrderExtractor extractor, SpeechToTextService stt, AudioStorageService storage,
                                  MediaDownloader media, ProductCatalogService catalog, ExtractionInterpreter interpreter,
                                  OrderWriter writer, OrderRepository orders, QuoteService quotes,
                                  ConversationService conversation, SseHub hub, AppMetrics metrics, AppProperties props,
                                  DeliveryZoneService zones, SaleService sales,
                                  org.springframework.jdbc.core.simple.JdbcClient jdbc,
                                  PrinterConfigService printerConfig) {
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
        this.sales = sales;
        this.jdbc = jdbc;
        this.printerConfig = printerConfig;
    }

    public OrderProcessingService(OrderExtractor extractor, SpeechToTextService stt, AudioStorageService storage,
                                  MediaDownloader media, ProductCatalogService catalog, ExtractionInterpreter interpreter,
                                  OrderWriter writer, OrderRepository orders, QuoteService quotes,
                                  ConversationService conversation, SseHub hub, AppMetrics metrics, AppProperties props,
                                  DeliveryZoneService zones, SaleService sales,
                                  org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this(extractor, stt, storage, media, catalog, interpreter, writer, orders, quotes, conversation, hub, metrics, props, zones, sales, jdbc, null);
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
        byte[] imageData = null;
        String imageMime = null;

        if (r.audio()) {
            text = transcribe(r);
            if (text == null) return;
        } else if (r.image()) {
            try {
                String key = r.storageKey();
                if (key == null) {
                    MediaDownloader.MediaFile f = media.download(r.mediaId());
                    imageMime = f.mime();
                    key = storage.save(f.data(), imageMime);
                    writer.attachImage(r.messageId(), key, r.mediaId(), imageMime, r.text());
                    imageData = f.data();
                } else {
                    imageData = storage.load(key);
                    imageMime = r.mime();
                }
            } catch (Exception e) {
                log.warn("Fallo cargando imagen del pedido {}: {}", r.orderId(), e.getMessage());
                metrics.aiError("image");
                writer.markFailed(r.orderId(), "FAILED", "No se pudo cargar la imagen; digitar manualmente");
                return;
            }
        }

        // 1. Si es solo un saludo sin productos previos en el pedido:
        if (!r.image() && !r.reply() && isGreetingOnly(text)) {
            boolean hasHistory = writer.hasPriorMessages(r.orderId());
            String greeting = hasHistory
                    ? "Buenas, ¿en qué te podemos colaborar hoy? Puedes dejarnos tu lista de mercado o preguntarnos lo que necesites."
                    : "¡Hola! Buenas 👋 ¿En qué te colaboramos hoy? Puedes enviarnos tu lista de mercado (por texto o nota de voz) o preguntarnos precios y lo que necesites.";
            conversation.send(r.orderId(), greeting, ConversationService.Kind.INFO, "ia");
            writer.markInquiryHandled(r.orderId(), "Saludo recibido y respondido.");
            return;
        }

        // 2. Si es una consulta directa de disponibilidad o precios de catálogo (solo si no es un pedido activo):
        if (!r.image() && !r.reply() && isPriceOrInventoryInquiry(text)) {
            String reply = buildCatalogOrPriceReply(text);
            conversation.send(r.orderId(), reply, ConversationService.Kind.INFO, "ia");
            writer.markInquiryHandled(r.orderId(), "Consulta sobre disponibilidad/precios respondida automáticamente.");
            return;
        }

        // 3. Si solicitan foto o imagen de un producto:
        if (!r.image() && isProductPhotoRequest(text)) {
            handleProductPhotoRequest(r, text);
            return;
        }

        // 4. Si el cliente pregunta cosas como tiempo de entrega / demora:
        if (!r.image() && isDeliveryTimeInquiry(text)) {
            handleDeliveryTimeInquiry(r, text);
            return;
        }

        // Extraer nombre y método de pago (con cambio si es efectivo) de forma determinística
        var currentRow = orders.find(r.orderId()).orElse(null);
        String lastOutbound = conversation.messages(r.orderId()).stream()
                .filter(m -> "OUTBOUND".equals(m.direction()))
                .reduce((first, second) -> second)
                .map(ConversationService.MessageView::content)
                .orElse(null);

        String extractedName = extractCustomerName(text, lastOutbound);
        if (extractedName != null) {
            writer.updateCustomerName(r.orderId(), extractedName);
        }

        Quote currentQuote = null;
        if (currentRow != null) {
            var currentItems = orders.items(r.orderId());
            if (!currentItems.isEmpty()) {
                currentQuote = quotes.quote(currentItems, PriceType.forCustomer(CustomerType.valueOf(currentRow.customerType())));
            }
        }
        BigDecimal currentTotal = currentQuote != null ? currentQuote.total() : null;

        PaymentInfo extractedPayment = extractPaymentMethod(text, currentTotal);
        if (extractedPayment != null) {
            writer.updatePaymentNote(r.orderId(), extractedPayment.note());
        }

        ExtractionRequest req = new ExtractionRequest(text,
                writer.recentTurns(r.orderId(), r.messageId()),
                r.reply() ? writer.currentItems(r.orderId()) : java.util.List.of(),
                catalog.candidates(text),
                zones.formatForPrompt(),
                writer.customerFavorites(r.orderId()));

        long t0 = System.nanoTime();
        AIOrderResponse ai;
        try {
            if (imageData != null) {
                ai = extractor.extractImage(imageData, imageMime, req);
            } else {
                ai = extractor.extract(req);
            }
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

        boolean autoPrint = (printerConfig != null)
                ? printerConfig.get().autoPrintWholesale()
                : (props.printing() != null && props.printing().autoPrintWholesale());

        if (autoPrint) {
            boolean hasTrulyDoubtfulLines = io.items().isEmpty()
                    || io.items().stream().anyMatch(it -> (it.quantity() == null && it.aiQuantity() == null)
                    || (it.aiAmbiguous() && it.productId() == null));
            boolean requiresVerification = hasTrulyDoubtfulLines;
            String reason = requiresVerification ? (io.note() != null ? io.note() : "Líneas con duda en pedido") : null;

            int graceSec = (printerConfig != null)
                    ? printerConfig.get().orderGraceSeconds()
                    : (props.printing() != null ? props.printing().wholesaleGraceSeconds() : 300);
            if (graceSec <= 0) {
                sales.autoApproveWholesale(r.orderId(), requiresVerification, reason, "ia_wholesale");
                return;
            }
            jdbc.sql("""
                    UPDATE orders SET requires_verification = :rv, verification_reason = :vr,
                           conversation_status = 'WAITING_FOR_MORE', version = version + 1
                    WHERE id = :id AND status IN ('RECEIVED','PROCESSING','REVIEW')
                    """).param("rv", requiresVerification).param("vr", reason).param("id", r.orderId()).update();
            hub.orderChanged(r.orderId());
            return;
        }

        autoActions(r.orderId(), io, text, r.audio());
    }

    /** Tarea programada que aprueba e imprime facturas mayoristas una vez vencido el margen de espera por adiciones */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10000)
    public void processPendingWholesaleAutoApprovals() {
        boolean autoPrint = (printerConfig != null)
                ? printerConfig.get().autoPrintWholesale()
                : (props.printing() != null && props.printing().autoPrintWholesale());
        if (!autoPrint) return;

        int graceSec = (printerConfig != null)
                ? printerConfig.get().orderGraceSeconds()
                : (props.printing() != null ? props.printing().wholesaleGraceSeconds() : 300);
        if (graceSec <= 0) return;

        List<Long> readyOrders = jdbc.sql("""
                SELECT o.id FROM orders o
                WHERE o.status = 'REVIEW'
                  AND o.ai_status = 'OK'
                  AND o.extracted_at <= NOW() - (:sec || ' seconds')::interval
                  AND NOT EXISTS (
                      SELECT 1 FROM conversation_message cm
                      WHERE cm.order_id = o.id
                        AND cm.created_at > NOW() - (:sec || ' seconds')::interval
                  )
                ORDER BY o.id ASC LIMIT 20
                """).param("sec", graceSec).query(Long.class).list();

        for (Long oId : readyOrders) {
            try {
                boolean hasTrulyDoubtfulLines = jdbc.sql("""
                        SELECT count(*) FROM order_item
                        WHERE order_id = :o
                          AND ((quantity IS NULL AND ai_quantity IS NULL) OR (ai_ambiguous AND product_id IS NULL))
                        """).param("o", oId).query(Integer.class).single() > 0
                        || jdbc.sql("SELECT count(*) FROM order_item WHERE order_id = :o").param("o", oId).query(Integer.class).single() == 0;

                String reason = hasTrulyDoubtfulLines ? "Líneas con duda en pedido" : null;
                sales.autoApproveWholesale(oId, hasTrulyDoubtfulLines, reason, "ia_wholesale_timer");
                hub.orderChanged(oId);
            } catch (Exception e) {
                log.warn("Fallo auto-aprobación diferida para pedido {}: {}", oId, e.getMessage());
            }
        }
    }

    /** Recuperación automática de pedidos pendientes si la app se reinició o hubo corte de conexión */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void onStartupRecoverPendingExtractions() {
        recoverPendingExtractions();
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    public void recoverPendingExtractions() {
        try {
            List<Long> pendingOrders = jdbc.sql("""
                    SELECT o.id FROM orders o
                    WHERE o.ai_status = 'PENDING'
                      AND o.status IN ('RECEIVED', 'PROCESSING', 'REVIEW')
                    ORDER BY o.id ASC LIMIT 10
                    """).query(Long.class).list();

            for (Long oId : pendingOrders) {
                try {
                    var msgRow = jdbc.sql("""
                            SELECT cm.id, cm.message_type, cm.content
                            FROM conversation_message cm
                            WHERE cm.order_id = :o AND cm.direction = 'INBOUND'
                            ORDER BY cm.id DESC LIMIT 1
                            """).param("o", oId).query((rs, n) -> new Object[]{
                                    rs.getLong(1), rs.getString(2), rs.getString(3)
                            }).optional().orElse(null);

                    if (msgRow == null) continue;
                    long msgId = (Long) msgRow[0];
                    String type = (String) msgRow[1];
                    String content = msgRow[2] == null ? "" : (String) msgRow[2];
                    boolean isAudio = "AUDIO".equalsIgnoreCase(type);
                    boolean isImage = "IMAGE".equalsIgnoreCase(type);

                    String storageKey = null;
                    String mime = null;
                    if (isAudio) {
                        var aRow = jdbc.sql("SELECT storage_key, mime_type FROM audio WHERE message_id = :m")
                                .param("m", msgId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional().orElse(null);
                        if (aRow != null) { storageKey = aRow[0]; mime = aRow[1]; }
                    } else if (isImage) {
                        var iRow = jdbc.sql("SELECT storage_key, mime_type FROM media_image WHERE message_id = :m")
                                .param("m", msgId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional().orElse(null);
                        if (iRow != null) { storageKey = iRow[0]; mime = iRow[1]; }
                    }

                    int msgCount = jdbc.sql("SELECT count(*) FROM conversation_message WHERE order_id = :o").param("o", oId).query(Integer.class).single();
                    boolean reply = msgCount > 1;

                    IntakeResult recoveryResult = new IntakeResult(oId, msgId, reply, isAudio, isImage, content, null, storageKey, mime);
                    log.info("Auto-recuperando procesamiento pendiente de IA para orden #{} (mensaje #{})", oId, msgId);
                    doProcess(recoveryResult);
                } catch (Exception e) {
                    log.warn("Fallo auto-recuperar orden #{}: {}", oId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Error en tarea programada de recuperación de pedidos: {}", e.getMessage());
        }
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
            var row = orders.find(orderId).orElse(null);
            if (row == null) return;

            var orderItems = orders.items(orderId);
            Quote quote = null;
            if (!orderItems.isEmpty()) {
                quote = quotes.quote(orderItems, PriceType.forCustomer(CustomerType.valueOf(row.customerType())));
            }

            // 1. Si el cliente preguntó por precios/costos de los productos ("3 libras de cebolla larga, que cuestan?"):
            // LA APP hace los cálculos de cotización exactos con el catálogo en Postgres:
            if (asksPrice(text) && quote != null && quote.complete() && !quote.lines().isEmpty()) {
                String priceMsg = buildPriceAnswerAndNextStep(quote, row);
                conversation.send(orderId, priceMsg, ConversationService.Kind.ASK, "ia");
                return;
            }

            // 2. Si el pedido ya tiene productos claros (GREEN):
            // IMPORTANTE: si es AUDIO, NO auto-confirmar sin revision humana del operario
            if (io.review() == ReviewStatus.GREEN && props.conversation().autoConfirmClearOrders() && !isAudio) {
                boolean wasAlreadyConfirmed = "CONFIRMED".equals(row.conversationStatus());
                if (wasAlreadyConfirmed) {
                    if (isPoliteAcknowledgment(text)) {
                        conversation.send(orderId, "Con mucho gusto, ya estamos en ello 😊", ConversationService.Kind.INFO, "ia");
                        return;
                    }
                    // Si ya estaba confirmado y no hubo cambio de productos o solo confirmó recepción, no repetir factura completa
                    return;
                }

                String missingQuestion = getNextMissingRequirementQuestion(row);
                if (missingQuestion != null) {
                    conversation.send(orderId, missingQuestion, ConversationService.Kind.ASK, "ia");
                    return;
                }

                Quote q = quotes.quote(orders.items(orderId), PriceType.forCustomer(CustomerType.valueOf(row.customerType())));
                String msg = buildOrderReceivedAndConfirmedMessage(q, row.address(), row.customerName(), row.notes());
                if (msg != null) {
                    conversation.send(orderId, msg, ConversationService.Kind.CONFIRM, "ia");
                    writer.markConfirmed(orderId);
                    return;
                }
            }

            // 3. Si la IA genero una respuesta o pregunta para el cliente (customerMessage), enviala
            if (io.customerMessage() != null && !io.customerMessage().isBlank() && io.action() == ConversationAction.ASK_CUSTOMER) {
                conversation.send(orderId, io.customerMessage(), ConversationService.Kind.ASK, "ia");
                return;
            }

            // 4. Si no esta confirmado en GREEN o requiere revision: aviso si es el primer contacto del pedido
            boolean hasPriorOutbound = conversation.messages(orderId).stream()
                    .anyMatch(m -> "OUTBOUND".equals(m.direction()));
            if (!hasPriorOutbound) {
                String ack = isAudio
                        ? "Hemos recibido tu nota de voz y la estamos revisando. En un momento te confirmamos."
                        : "Hemos recibido tu pedido y lo estamos revisando. En un momento te confirmamos.";
                conversation.send(orderId, ack, ConversationService.Kind.INFO, "ia");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo ejecutar la respuesta automatica del pedido {}: {}", orderId, e.getMessage());
        }
    }

    private String buildOrderReceivedAndConfirmedMessage(Quote quote, String address) {
        return buildOrderReceivedAndConfirmedMessage(quote, address, null, null);
    }

    private String buildOrderReceivedAndConfirmedMessage(Quote quote, String address, String customerName, String notes) {
        if (quote == null || !quote.complete()) return null;
        StringBuilder sb = new StringBuilder("¡Listo! Hemos registrado tu pedido y lo estamos preparando:\n\n");
        for (var l : quote.lines()) {
            sb.append("• ").append(Money.qty(l.quantity())).append(' ')
              .append(ConfirmationMessageBuilder.unitWord(l.unit(), l.quantity()))
              .append(" de ").append(l.productName()).append('\n');
        }
        sb.append("\nTotal estimado: ").append(Money.format(quote.total()));
        if (customerName != null && !customerName.isBlank()) {
            sb.append("\n👤 Cliente: ").append(customerName);
        }
        if (address != null && !address.isBlank()) {
            boolean isPickup = isPickupAddress(address);
            sb.append("\n📍 Entrega: ").append(isPickup ? "Recoges en tienda / Punto físico" : address);
        }
        if (notes != null && !notes.isBlank()) {
            for (String part : notes.split("\\|")) {
                String p = part.trim();
                if (p.startsWith("Pago:")) {
                    sb.append("\n💵 ").append(p);
                } else if (p.startsWith("Horario:")) {
                    sb.append("\n⏰ ").append(p);
                }
            }
        }
        sb.append("\n\nEn un momento te confirmamos el despacho 😊");
        return sb.toString();
    }

    private String buildPriceAnswerAndNextStep(Quote q, OrderRow row) {
        StringBuilder sb = new StringBuilder();
        if (q.lines().size() == 1) {
            var l = q.lines().get(0);
            sb.append("Las ").append(Money.qty(l.quantity())).append(' ')
              .append(ConfirmationMessageBuilder.unitWord(l.unit(), l.quantity()))
              .append(" de ").append(l.productName()).append(" te salen en ")
              .append(Money.format(l.subtotal()))
              .append(" (").append(Money.format(l.appliedPrice())).append(" / ")
              .append(ConfirmationMessageBuilder.unitWord(l.unit(), BigDecimal.ONE)).append(").");
        } else {
            sb.append("El valor de tus productos es:\n");
            for (var l : q.lines()) {
                sb.append("• ").append(Money.qty(l.quantity())).append(' ')
                  .append(ConfirmationMessageBuilder.unitWord(l.unit(), l.quantity()))
                  .append(" de ").append(l.productName()).append(": ")
                  .append(Money.format(l.subtotal())).append("\n");
            }
            sb.append("Total: ").append(Money.format(q.total())).append(".\n");
        }

        String next = getNextMissingRequirementQuestion(row);
        if (next != null) {
            sb.append("\n\n").append(next);
        }
        return sb.toString();
    }

    public static boolean isPickupAddress(String address) {
        if (address == null) return false;
        String a = address.toLowerCase(Locale.ROOT);
        return a.contains("recoge") || a.contains("tienda") || a.contains("punto")
                || a.contains("local") || a.contains("físico") || a.contains("fisico");
    }

    public static boolean isCleanConfirmedName(String name) {
        if (name == null || name.isBlank()) return false;
        String s = name.trim();
        if (s.matches("^\\+?\\d+$")) return false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int type = Character.getType(cp);
            if (type == Character.SURROGATE || type == Character.OTHER_SYMBOL) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return s.matches("^[A-Za-zÁ-ÿ\\s.'-]+$") && s.length() >= 2;
    }

    public static boolean hasPaymentInfo(String notes) {
        if (notes == null || notes.isBlank()) return false;
        String n = notes.toLowerCase(Locale.ROOT);
        return n.contains("pago:") || n.contains("transferencia") || n.contains("llave")
                || n.contains("efectivo") || n.contains("nequi") || n.contains("daviplata");
    }

    public static boolean needsCashBillClarification(String notes) {
        if (notes == null) return false;
        String n = notes.toLowerCase(Locale.ROOT);
        return n.contains("pago: efectivo") && !n.contains("billete") && !n.contains("paga con") && !n.contains("exacto");
    }

    public String getNextMissingRequirementQuestion(OrderRow row) {
        if (row == null) return null;

        // 1. ¿Tiene dirección o método de entrega definido?
        // Hacemos toda la pregunta de una para gastar los mínimos tokens y mensajes posibles:
        if (row.address() == null || row.address().isBlank()) {
            return "¿Es para domicilio o recoges en la tienda?\n"
                    + "Si es domicilio, por favor indícanos de una vez:\n"
                    + "1. Tu dirección exacta y barrio.\n"
                    + "2. ¿A nombre de quién dejamos el pedido?\n"
                    + "3. ¿Cómo pagas? (Transferencia / Llave o Efectivo, y con qué billete cancelas para el cambio).";
        }

        boolean isPickup = isPickupAddress(row.address());
        boolean hasName = isCleanConfirmedName(row.customerName());

        // 2. Si es para recoger en tienda:
        if (isPickup) {
            if (!hasName) {
                return "¡Listo! Lo preparamos para recoger en tienda. ¿A nombre de quién dejamos registrado el pedido?";
            }
            return null; // Todo completo para recoger en tienda
        }

        // 3. Si es domicilio y falta algo, preguntamos puntualmente lo que falta:
        boolean hasPayment = hasPaymentInfo(row.notes());
        boolean needsBill = needsCashBillClarification(row.notes());

        if (!hasName && !hasPayment) {
            return "¡Listo! Para programar tu despacho a " + row.address() + ":\n"
                    + "• ¿A nombre de quién dejamos el pedido?\n"
                    + "• ¿Cómo vas a pagar? (Transferencia / Llave o Efectivo, y con qué billete cancelas para el cambio).";
        }

        if (!hasName) {
            return "¿A nombre de quién dejamos registrado el pedido?";
        }

        if (!hasPayment) {
            return "¿Cómo vas a realizar el pago? (Transferencia / Llave o Efectivo. Si es en efectivo, ¿con qué billete cancelas para enviarte el cambio exacto?).";
        }

        if (needsBill) {
            return "¿Con qué billete cancelas en efectivo? (Por ejemplo: billete de $20.000, $50.000, $100.000 o valor exacto) para que el repartidor lleve el cambio.";
        }

        return null; // Todo completo para domicilio!
    }

    public static boolean asksPrice(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("cuesta") || t.contains("cuestan") || t.contains("vale") || t.contains("valen")
                || t.contains("precio") || t.contains("precios") || t.contains("cuanto") || t.contains("cuánto")
                || t.contains("a como") || t.contains("a cómo") || t.contains("valor") || t.contains("cotizar")
                || t.contains("total") || t.contains("cuenta");
    }

    public static String extractCustomerName(String text, String botLastQuestion) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();

        var m = Pattern.compile("(?i)(?:a\\s+nombre\\s+de|nombre:?|para:?|soy|me\\s+llamo|de\\s+parte\\s+de)\\s+([A-Za-zÁ-ÿ\\s]{2,40}?)(\\s*,|\\s*\\.|\\s+(?:y|pago|calle|carrera|cra|cll|barrio|tel|en\\s+efectivo|por\\s+transferencia)|$)")
                .matcher(t);
        if (m.find()) {
            String candidate = m.group(1).trim();
            if (isValidName(candidate)) {
                return capitalizeName(candidate);
            }
        }

        if (botLastQuestion != null && botLastQuestion.toLowerCase(Locale.ROOT).contains("nombre de quién")) {
            String[] words = t.split("\\s+");
            if (words.length >= 1 && words.length <= 4) {
                String candidate = t.replaceAll("[,.]", "").trim();
                if (isValidName(candidate)) {
                    return capitalizeName(candidate);
                }
            }
        }
        return null;
    }

    private static boolean isValidName(String s) {
        if (s == null || s.isBlank() || s.length() < 2 || s.length() > 60) return false;
        String lower = s.toLowerCase(Locale.ROOT);
        List<String> stopWords = List.of("domicilio", "tienda", "punto", "fisico", "físico", "efectivo",
                "transferencia", "llave", "nequi", "daviplata", "cebolla", "tomate", "papa", "kilos",
                "libras", "hola", "buenas", "si", "sí", "no", "gracias", "billete", "por favor");
        for (String sw : stopWords) {
            if (lower.equals(sw) || lower.startsWith(sw + " ") || lower.endsWith(" " + sw)) {
                return false;
            }
        }
        return s.matches("^[A-Za-zÁ-ÿ\\s]+$");
    }

    private static String capitalizeName(String name) {
        String[] parts = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.toString();
    }

    public record PaymentInfo(String note, BigDecimal billAmount, boolean isCash, boolean isTransfer) {}

    public static PaymentInfo extractPaymentMethod(String text, BigDecimal orderTotal) {
        if (text == null || text.isBlank()) return null;
        String t = text.toLowerCase(Locale.ROOT);

        boolean transfer = t.contains("transferencia") || t.contains("llave") || t.contains("nequi")
                || t.contains("daviplata") || t.contains("bancolombia") || t.contains("transfiya")
                || t.contains("bre-b") || t.contains("breb") || t.contains("transferir");

        if (transfer) {
            String detail = "Pago: Transferencia / Llave";
            if (t.contains("nequi")) detail = "Pago: Transferencia (Nequi)";
            else if (t.contains("daviplata")) detail = "Pago: Transferencia (Daviplata)";
            else if (t.contains("bancolombia")) detail = "Pago: Transferencia (Bancolombia)";
            else if (t.contains("llave")) detail = "Pago: Transferencia / Llave (Bre-B)";
            return new PaymentInfo(detail, null, false, true);
        }

        boolean cash = t.contains("efectivo") || t.contains("plata") || t.contains("billete")
                || t.contains("cambio") || t.contains("devuelta") || t.contains("vueltas")
                || t.contains("sencillo") || t.contains("just") || t.contains("exact");

        boolean billMentioned = t.matches(".*\\b(con|billete\\s*de|de|pago\\s*con)\\s*(\\d+|cien|cincuenta|veinte|diez)\\b.*");

        if (cash || billMentioned) {
            if (t.contains("exact") || t.contains("just") || t.contains("sencillo") || t.contains("sin cambio")) {
                return new PaymentInfo("Pago: Efectivo (valor exacto, sin cambio)", null, true, false);
            }

            BigDecimal bill = null;
            if (t.matches(".*\\b(100\\s*mil|100000|100\\.000|cien\\s*mil|de\\s*cien)\\b.*")) {
                bill = new BigDecimal("100000");
            } else if (t.matches(".*\\b(50\\s*mil|50000|50\\.000|cincuenta\\s*mil|de\\s*cincuenta|con\\s*50)\\b.*")) {
                bill = new BigDecimal("50000");
            } else if (t.matches(".*\\b(20\\s*mil|20000|20\\.000|veinte\\s*mil|de\\s*veinte|con\\s*20)\\b.*")) {
                bill = new BigDecimal("20000");
            } else if (t.matches(".*\\b(10\\s*mil|10000|10\\.000|diez\\s*mil|de\\s*diez|con\\s*10)\\b.*")) {
                bill = new BigDecimal("10000");
            } else {
                var m = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{3})*|\\d+)\\s*mil\\b").matcher(t);
                if (m.find()) {
                    try {
                        String num = m.group(1).replace(".", "");
                        long val = Long.parseLong(num) * 1000;
                        bill = BigDecimal.valueOf(val);
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (bill != null) {
                String note;
                if (orderTotal != null && orderTotal.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal change = bill.subtract(orderTotal);
                    if (change.compareTo(BigDecimal.ZERO) >= 0) {
                        note = "Pago: Efectivo (paga con " + Money.format(bill) + " - cambio: " + Money.format(change) + ")";
                    } else {
                        note = "Pago: Efectivo (paga con " + Money.format(bill) + ")";
                    }
                } else {
                    note = "Pago: Efectivo (paga con billete de " + Money.format(bill) + ")";
                }
                return new PaymentInfo(note, bill, true, false);
            }

            return new PaymentInfo("Pago: Efectivo", null, true, false);
        }

        return null;
    }

    public static boolean isPoliteAcknowledgment(String text) {
        if (text == null || text.isBlank()) return false;
        String t = TextTokens.fold(text).trim();
        return t.equals("gracias") || t.equals("muchas gracias") || t.equals("mil gracias")
                || t.equals("perfecto") || t.equals("perfecto gracias") || t.equals("vale")
                || t.equals("vale gracias") || t.equals("ok") || t.equals("ok gracias")
                || t.equals("listo") || t.equals("listo gracias") || t.equals("bueno")
                || t.equals("excelente") || t.equals("dale") || t.equals("bien")
                || t.contains("por que repites") || t.contains("porque repites");
    }

    public boolean isPriceOrInventoryInquiry(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);

        // Si contiene verbos de pedido directo, NUNCA es consulta simple de precios:
        if (t.contains("me vende") || t.contains("me manda") || t.contains("enviar") || t.contains("mandeme")
                || t.contains("mándeme") || t.contains("deme") || t.contains("dame") || t.contains("quiero")
                || t.contains("llevar") || t.contains("mande") || t.contains("traigame") || t.contains("tráigame")
                || t.contains("regalame") || t.contains("regálame") || t.contains("regale") || t.contains("regáleme")
                || t.contains("necesito") || t.contains("ponme") || t.contains("póngame") || t.contains("agregue")
                || t.contains("agrega") || t.contains("agregame") || t.contains("agrégame") || t.contains("traer")
                || t.contains("traiga")) {
            return false;
        }

        // Si pregunta explícitamente por precio o costo:
        boolean asksPrice = t.contains("precio") || t.contains("precios") || t.contains("cuanto vale") || t.contains("cuánto vale")
                || t.contains("cuanto cuesta") || t.contains("cuánto cuesta") || t.contains("a como") || t.contains("a cómo")
                || t.contains("costo") || t.contains("valor");
        if (asksPrice) return true;

        // Si tiene cantidades explícitas (ej: "2 kilos", "2 libras", "2mil"), es un pedido:
        if (t.matches(".*\\b\\d+\\s*(libras?|kilos?|kg|lb|unidades?|mil)\\b.*")) {
            return false;
        }

        return t.contains("catalogo") || t.contains("catálogo")
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

    public boolean isProductPhotoRequest(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("foto") || t.contains("fotos") || t.contains("imagen") || t.contains("imagenes") || t.contains("imágenes");
    }

    private void handleProductPhotoRequest(IntakeResult r, String text) {
        var all = catalog.list("", false);
        List<String> tokens = TextTokens.significant(text);
        ProductCatalogService.ProductView matched = null;
        for (var p : all) {
            String pName = p.name().toLowerCase(Locale.ROOT);
            for (String tok : tokens) {
                String tl = tok.toLowerCase(Locale.ROOT);
                if (tl.length() >= 3 && !List.of("foto","fotos","imagen","imagenes","imágenes","como","cómo","mira","ve","tiene","tienen","compartir","mandar","enviar").contains(tl)
                        && pName.contains(tl)) {
                    matched = p;
                    break;
                }
            }
            if (matched != null) break;
        }

        if (matched != null && matched.imageUrl() != null && !matched.imageUrl().isBlank()) {
            String caption = "Aquí tienes la foto de " + matched.name() + " 📸. ¿Deseas que te agreguemos alguna cantidad a tu pedido?";
            conversation.sendImage(r.orderId(), matched.imageUrl(), caption, "ia");
            writer.markInquiryHandled(r.orderId(), "Foto de producto enviada: " + matched.name());
        } else if (matched != null) {
            String msg = "¡Hola! En este momento no tenemos foto cargada de " + matched.name() + ", pero te aseguramos que está fresca y de excelente calidad. ¿Deseas ordenar alguna cantidad?";
            conversation.send(r.orderId(), msg, ConversationService.Kind.INFO, "ia");
            writer.markInquiryHandled(r.orderId(), "Consulta de foto (" + matched.name() + " no tiene foto cargada)");
        } else {
            String msg = "¡Hola! Con gusto te enviamos fotos de nuestros productos frescos. ¿De cuál de nuestros productos te gustaría ver foto (por ejemplo papaya, tomate, aguacate)? 😊";
            conversation.send(r.orderId(), msg, ConversationService.Kind.INFO, "ia");
            writer.markInquiryHandled(r.orderId(), "Consulta general de fotos respondida.");
        }
    }

    public boolean isDeliveryTimeInquiry(String text) {
        if (text == null || text.isBlank()) return false;
        String t = TextTokens.fold(text).toLowerCase(Locale.ROOT);
        return t.contains("cuanto se demora") || t.contains("se demora") || t.contains("demora mucho")
                || (t.contains("a que hora") && (t.contains("llega") || t.contains("entrega") || t.contains("traen") || t.contains("envia")))
                || t.contains("cuanto tarda") || t.contains("cuanto falta")
                || t.contains("ya viene") || t.contains("ya salio") || t.contains("donde viene")
                || (t.contains("cuando") && (t.contains("llega") || t.contains("traen") || t.contains("entrega")))
                || t.contains("tiempo de entrega") || t.contains("tiempo se demora") || t.contains("tiempo tardan")
                || (t.contains("tiempo") && (t.contains("entrega") || t.contains("demora") || t.contains("llegar")));
    }

    private void handleDeliveryTimeInquiry(IntakeResult r, String text) {
        // "si el usuario pregunta cosas como tiempo que sea el humano quien responda"
        String note = "Cliente pregunta por tiempos de entrega/estado. Requiere respuesta humana.";
        String suggested = "¡Hola! Tu pedido se encuentra en preparación/camino y te llegará aproximadamente en [XX] minutos 🛵💨";
        writer.markWaitingForOperator(r.orderId(), note, suggested);
    }
}
