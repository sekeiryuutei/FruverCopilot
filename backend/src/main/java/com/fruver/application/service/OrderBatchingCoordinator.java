package com.fruver.application.service;

import com.fruver.application.dto.IntakeResult;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinador de ventana de espera (Debounce) y empaquetado de mensajes por pedido.
 * Cuando un cliente envía una ráfaga continua (ej: audio, luego foto, luego texto, luego otra foto),
 * no interrumpe al cliente con preguntas prematuras ni dispara llamadas separadas a la IA.
 * Acumula todos los medios en la ventana de espera y ejecuta una única extracción consolidada
 * ahorrando tokens, respetando los límites de RPM y entregando el pedido completo.
 */
@Service
public class OrderBatchingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(OrderBatchingCoordinator.class);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "order-debounce");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<Long, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Instant> burstStarts = new ConcurrentHashMap<>();
    private final OrderProcessingService processing;
    private final AppProperties props;
    private final PrinterConfigService printerConfig;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderBatchingCoordinator(OrderProcessingService processing, AppProperties props,
                                    PrinterConfigService printerConfig) {
        this.processing = processing;
        this.props = props;
        this.printerConfig = printerConfig;
    }

    public OrderBatchingCoordinator(OrderProcessingService processing, AppProperties props) {
        this(processing, props, null);
    }

    public void scheduleProcessing(IntakeResult result) {
        long orderId = result.orderId();
        int debounceSec = 20;
        if (printerConfig != null && printerConfig.get().customerDebounceSeconds() >= 0) {
            debounceSec = printerConfig.get().customerDebounceSeconds();
        } else if (props.conversation() != null) {
            debounceSec = props.conversation().debounceSeconds();
        }
        int maxSec = (props.conversation() != null) ? props.conversation().debounceMaxSeconds() : 90;

        if (debounceSec <= 0) {
            // Modo inmediato (tests o sin debounce configurado)
            processing.process(result);
            return;
        }

        Instant now = Instant.now();
        Instant first = burstStarts.computeIfAbsent(orderId, k -> now);
        long elapsed = Duration.between(first, now).toSeconds();

        ScheduledFuture<?> old = timers.remove(orderId);
        if (old != null) {
            old.cancel(false);
        }

        if (elapsed >= maxSec) {
            log.info("Pedido #{}: tiempo máximo de espera de ráfaga alcanzado ({}s >= {}s). Procesando lote completo ya...",
                    orderId, elapsed, maxSec);
            burstStarts.remove(orderId);
            processing.processBatch(orderId, result);
            return;
        }

        long delay = Math.min(debounceSec, maxSec - elapsed);
        log.info("Pedido #{}: mensaje recibido. Esperando {}s para consolidar lote completo del cliente...", orderId, delay);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            try {
                timers.remove(orderId);
                burstStarts.remove(orderId);
                log.info("Pedido #{}: ventana de espera completada. Ejecutando extracción consolidada con IA...", orderId);
                processing.processBatch(orderId, result);
            } catch (Exception e) {
                log.error("Fallo ejecutando lote consolidado para pedido #{}", orderId, e);
            }
        }, delay, TimeUnit.SECONDS);

        timers.put(orderId, future);
    }
}

