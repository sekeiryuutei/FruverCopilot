package com.fruver.application.service;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fruver.application.port.MessagingGateway;
import com.fruver.application.port.PrintStrategy;
import com.fruver.config.AppProperties;
import com.fruver.infrastructure.printing.PrintStrategyFactory;

/**
 * Worker de impresión (patrón outbox sobre print_job y Strategy + Factory para despacho).
 * Reclama trabajos con FOR UPDATE SKIP LOCKED, selecciona la estrategia (Correo Brother, IPP, ESC/POS)
 * y emite eventos en tiempo real para reflejar el estado (PRINTING -> PRINTED / PRINT_FAILED) con spinner.
 */
@Service
public class PrintService {

    private static final Logger log = LoggerFactory.getLogger(PrintService.class);

    record Job(long id, long saleId, int attempts, boolean reprint, String targetPrinter) {}

    private final JdbcClient jdbc;
    private final TicketService tickets;
    private final PrintStrategyFactory strategyFactory;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final AppMetrics metrics;
    private final SseHub hub;
    private final MessagingGateway gateway;
    private final PrinterConfigService printerConfig;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PrintService(JdbcClient jdbc, TicketService tickets, PrintStrategyFactory strategyFactory,
                        TransactionTemplate tx, AppProperties props, AppMetrics metrics, SseHub hub,
                        MessagingGateway gateway, PrinterConfigService printerConfig) {
        this.jdbc = jdbc;
        this.tickets = tickets;
        this.strategyFactory = strategyFactory;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
        this.hub = hub;
        this.gateway = gateway;
        this.printerConfig = printerConfig;
    }

    @Scheduled(fixedDelayString = "${app.printing.poll-ms:1000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        try {
            while (processOne()) { /* vaciar la cola */ }
        } catch (RuntimeException e) {
            log.error("Error en el worker de impresion", e);
        } finally {
            running.set(false);
        }
    }

    /** @return true si proceso un trabajo (para seguir con el siguiente). */
    public boolean processOne() {
        Optional<Job> claimed = claim();
        if (claimed.isEmpty()) return false;
        Job job = claimed.get();
        String location = job.targetPrinter() != null ? job.targetPrinter() : printerConfig.currentTargetPrinter("AUTO");

        // Notificar de inmediato que la venta pasó a estado PRINTING (spinner en UI)
        try {
            jdbc.sql("UPDATE sale SET print_status = 'PRINTING', print_error = NULL WHERE id = :s AND print_status <> 'PRINTED'")
                    .param("s", job.saleId()).update();
            jdbc.sql("SELECT order_id FROM sale WHERE id = :s").param("s", job.saleId()).query(Long.class).optional().ifPresent(hub::orderChanged);
        } catch (Exception e) {
            log.debug("No se pudo notificar inicio de impresión: {}", e.getMessage());
        }

        try {
            TicketData ticket = tickets.build(job.saleId(), job.reprint());
            if (ticket.lines() == null || ticket.lines().isEmpty()) {
                log.warn("Cancelando trabajo de impresión #{}: la venta #{} no contiene productos para imprimir.", job.id(), job.saleId());
                jdbc.sql("UPDATE print_job SET status = 'FAILED', last_error = 'La orden no tiene productos para imprimir', updated_at = now() WHERE id = :id")
                        .param("id", job.id()).update();
                jdbc.sql("UPDATE sale SET print_status = 'PRINT_FAILED', print_error = 'Sin productos' WHERE id = :s")
                        .param("s", job.saleId()).update();
                jdbc.sql("SELECT order_id FROM sale WHERE id = :s").param("s", job.saleId()).query(Long.class).optional().ifPresent(hub::orderChanged);
                return true;
            }
            PrintStrategy strategy = strategyFactory.getStrategy(location);
            log.info("Despachando impresión de orden #{} con estrategia {} en {}", ticket.orderNumber(), strategy.getStrategyName(), location);

            strategy.print(ticket, location);

            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE print_job SET status = 'DONE', last_error = NULL, printed_printer = :p, updated_at = now() WHERE id = :id")
                        .param("id", job.id()).param("p", location).update();
                jdbc.sql("UPDATE sale SET print_status = 'PRINTED', print_error = NULL, printed_printer = :p, printed_at = COALESCE(printed_at, now()) WHERE id = :s")
                        .param("s", job.saleId()).param("p", location).update();
            });
            metrics.print("ok");
            if (!job.reprint()) {
                sendPrintedConfirmation(job.saleId());
            }
        } catch (Exception e) {
            fail(job, e instanceof RuntimeException re ? re : new RuntimeException(e));
        }
        jdbc.sql("SELECT order_id FROM sale WHERE id = :s").param("s", job.saleId()).query(Long.class).optional().ifPresent(hub::orderChanged);
        return true;
    }

    private void sendPrintedConfirmation(long saleId) {
        try {
            var saleInfo = jdbc.sql("SELECT order_id, customer_phone FROM sale WHERE id = :s")
                    .param("s", saleId)
                    .query((rs, n) -> new String[]{ String.valueOf(rs.getLong(1)), rs.getString(2) })
                    .optional().orElse(null);
            if (saleInfo != null && saleInfo[1] != null && !saleInfo[1].isBlank()) {
                long orderId = Long.parseLong(saleInfo[0]);
                String phone = saleInfo[1];
                String confirmMsg = "🙏🙏👍";

                // Buscar external_message_id para reaccionar o responder directamente al mensaje del pedido
                String externalMsgId = jdbc.sql("SELECT external_message_id FROM conversation_message WHERE order_id = :o AND direction = 'INBOUND' AND external_message_id IS NOT NULL ORDER BY id DESC LIMIT 1")
                        .param("o", orderId).query(String.class).optional().orElse(null);

                // 1. Reacción emoji nativa '🙏' al mensaje del cliente (sin enviar burbuja de texto)
                if (gateway instanceof com.fruver.infrastructure.whatsapp.strategy.WhatsAppServiceContext ctx) {
                    if (externalMsgId != null && !externalMsgId.isBlank()) {
                        try {
                            ctx.sendReaction(phone, externalMsgId, "🙏");
                        } catch (Exception re) {
                            log.debug("No se pudo enviar reacción emoji a {}: {}", phone, re.getMessage());
                        }
                    }
                }

                // 2. Respuesta de texto con confirmación '🙏🙏 bien' citando el mensaje del cliente
                try {
                    String deliveryStatus;
                    if (gateway instanceof com.fruver.infrastructure.whatsapp.strategy.WhatsAppServiceContext ctx) {
                        deliveryStatus = ctx.sendText(phone, confirmMsg, null, externalMsgId).name();
                    } else {
                        deliveryStatus = gateway.sendText(phone, confirmMsg).name();
                    }
                    jdbc.sql("INSERT INTO conversation_message(order_id, direction, message_type, content, delivery_status) VALUES (:o, 'OUTBOUND', 'TEXT', :c, :d)")
                            .param("o", orderId).param("c", confirmMsg).param("d", deliveryStatus).update();
                    hub.orderChanged(orderId);
                    log.info("Confirmación '{}' enviada por WhatsApp al cliente {} para pedido #{}", confirmMsg, phone, orderId);
                } catch (Exception te) {
                    log.warn("No se pudo enviar mensaje de confirmación '{}': {}", confirmMsg, te.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("No se pudo enviar confirmación de impresión por WhatsApp: {}", e.getMessage());
        }
    }

    /** Reintenta de inmediato todos los pedidos con fallo de impresion o pendientes por papel/red */
    public int reprintAllPending() {
        String target = printerConfig.currentTargetPrinter("AUTO");
        int updated = jdbc.sql("""
                UPDATE print_job SET status = 'PENDING', attempts = 0, last_error = NULL,
                       target_printer = :tp, next_attempt_at = now(), updated_at = now()
                WHERE status IN ('FAILED', 'PRINTING')
                   OR (status = 'PENDING' AND last_error IS NOT NULL)
                """).param("tp", target).update();

        // Si hay ventas marcadas con PRINT_FAILED sin print_job pendiente, re-encolarlas
        jdbc.sql("""
                INSERT INTO print_job(sale_id, reprint, target_printer)
                SELECT s.id, TRUE, :tp FROM sale s
                WHERE s.print_status = 'PRINT_FAILED'
                  AND NOT EXISTS (SELECT 1 FROM print_job pj WHERE pj.sale_id = s.id AND pj.status IN ('PENDING', 'PRINTING'))
                """).param("tp", target).update();

        tick();
        return updated;
    }

    @Scheduled(fixedDelay = 60000)
    public void autoCheckAndReprint() {
        try {
            int count = reprintAllPending();
            if (count > 0) {
                log.info("Auto-reintento de impresión: {} trabajos re-encolados automáticamente.", count);
            }
        } catch (Exception e) {
            log.debug("Error en auto-reintento de impresión: {}", e.getMessage());
        }
    }

    private Optional<Job> claim() {
        String currentTarget = printerConfig.currentTargetPrinter("AUTO");
        return jdbc.sql("""
                UPDATE print_job SET status = 'PRINTING', attempts = attempts + 1, updated_at = now(),
                       target_printer = COALESCE(target_printer, :tp)
                WHERE id = (SELECT id FROM print_job
                            WHERE (status = 'PENDING' AND next_attempt_at <= now())
                               OR (status = 'PRINTING' AND updated_at < now() - interval '2 minutes')
                            ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, sale_id, attempts, reprint, target_printer
                """).param("tp", currentTarget)
                .query((rs, n) -> new Job(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBoolean(4), rs.getString(5))).optional();
    }

    private void fail(Job job, RuntimeException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        msg = msg.length() > 250 ? msg.substring(0, 250) : msg;
        log.warn("Impresion fallida (venta {}, intento {}): {}", job.saleId(), job.attempts(), msg);
        metrics.print("failed");
        final String error = msg;
        if (job.attempts() < props.printing().maxAttempts()) {
            jdbc.sql("UPDATE print_job SET status = 'PENDING', last_error = :e, next_attempt_at = now() + (:w * interval '1 second'), updated_at = now() WHERE id = :id")
                    .param("e", error).param("w", job.attempts() * 5).param("id", job.id()).update();
        } else if ("CASA".equalsIgnoreCase(job.targetPrinter())) {
            log.warn("Impresora CASA agotó sus {} intentos para venta #{}. Cambiando destino a OFICINA (Email Brother)...", job.attempts(), job.saleId());
            jdbc.sql("UPDATE print_job SET status = 'PENDING', target_printer = 'OFICINA', attempts = 0, last_error = NULL, next_attempt_at = now() WHERE id = :id")
                    .param("id", job.id()).update();
        } else {
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE print_job SET status = 'FAILED', last_error = :e, updated_at = now() WHERE id = :id").param("e", error).param("id", job.id()).update();
                jdbc.sql("UPDATE sale SET print_status = 'PRINT_FAILED', print_error = :e WHERE id = :s").param("e", error).param("s", job.saleId()).update();
            });
        }
    }
}
