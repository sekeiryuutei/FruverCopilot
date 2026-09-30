package com.fruver.application.service;

import com.fruver.application.port.ThermalPrinterService;
import com.fruver.config.AppProperties;
import com.fruver.infrastructure.printing.EscPosEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker de impresion (patron outbox sobre print_job). Reclama trabajos con FOR UPDATE SKIP LOCKED,
 * imprime FUERA de cualquier transaccion y luego registra el resultado. Reintenta con espera creciente.
 */
@Service
public class PrintService {

    private static final Logger log = LoggerFactory.getLogger(PrintService.class);

    record Job(long id, long saleId, int attempts, boolean reprint) {}

    private final JdbcClient jdbc;
    private final TicketService tickets;
    private final ThermalPrinterService printer;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final AppMetrics metrics;
    private final SseHub hub;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PrintService(JdbcClient jdbc, TicketService tickets, ThermalPrinterService printer, TransactionTemplate tx,
                        AppProperties props, AppMetrics metrics, SseHub hub) {
        this.jdbc = jdbc;
        this.tickets = tickets;
        this.printer = printer;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
        this.hub = hub;
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
        try {
            byte[] payload = EscPosEncoder.encode(TicketFormatter.format(tickets.build(job.saleId(), job.reprint()), props.printing().columns()));
            printer.print(payload);
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE print_job SET status = 'DONE', last_error = NULL, updated_at = now() WHERE id = :id").param("id", job.id()).update();
                jdbc.sql("UPDATE sale SET print_status = 'PRINTED', print_error = NULL, printed_at = COALESCE(printed_at, now()) WHERE id = :s")
                        .param("s", job.saleId()).update();
            });
            metrics.print("ok");
        } catch (RuntimeException e) {
            fail(job, e);
        }
        jdbc.sql("SELECT order_id FROM sale WHERE id = :s").param("s", job.saleId()).query(Long.class).optional().ifPresent(hub::orderChanged);
        return true;
    }

    private Optional<Job> claim() {
        return jdbc.sql("""
                UPDATE print_job SET status = 'PRINTING', attempts = attempts + 1, updated_at = now()
                WHERE id = (SELECT id FROM print_job
                            WHERE (status = 'PENDING' AND next_attempt_at <= now())
                               OR (status = 'PRINTING' AND updated_at < now() - interval '2 minutes')
                            ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, sale_id, attempts, reprint
                """).query((rs, n) -> new Job(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBoolean(4))).optional();
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
        } else {
            tx.executeWithoutResult(s -> {
                jdbc.sql("UPDATE print_job SET status = 'FAILED', last_error = :e, updated_at = now() WHERE id = :id").param("e", error).param("id", job.id()).update();
                jdbc.sql("UPDATE sale SET print_status = 'PRINT_FAILED', print_error = :e WHERE id = :s").param("e", error).param("s", job.saleId()).update();
            });
        }
    }
}
