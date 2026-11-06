package com.fruver.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.BlockingAlert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio para registrar y publicar en tiempo real alertas de errores bloqueantes
 * (fallos en auto-aprobación, facturación atascada o errores críticos de impresión).
 */
@Service
public class BlockingAlertService {

    private static final Logger log = LoggerFactory.getLogger(BlockingAlertService.class);

    private final Map<String, BlockingAlert> activeAlerts = new ConcurrentHashMap<>();
    private final SseHub hub;
    private final ObjectMapper objectMapper;

    public BlockingAlertService(SseHub hub, ObjectMapper objectMapper) {
        this.hub = hub;
        this.objectMapper = objectMapper;
    }

    public BlockingAlert raise(String id, String type, String title, String message, Long orderId, Long saleId) {
        if (id == null || id.isBlank()) return null;
        BlockingAlert alert = new BlockingAlert(id, type != null ? type : "CRITICAL", title, message, orderId, saleId, Instant.now());
        activeAlerts.put(id, alert);
        log.warn("Alerta bloqueante registrada [{}]: {} - {}", id, title, message);

        try {
            String json = objectMapper.writeValueAsString(alert);
            hub.publish("blocking-alert", json);
        } catch (Exception e) {
            log.error("Error serializando alerta bloqueante para SSE: {}", e.getMessage());
        }
        return alert;
    }

    public boolean dismiss(String id) {
        if (id == null) return false;
        BlockingAlert removed = activeAlerts.remove(id);
        if (removed != null) {
            hub.publish("blocking-alert-dismiss", id);
            log.info("Alerta bloqueante descartada [{}]", id);
            return true;
        }
        return false;
    }

    public List<BlockingAlert> getActiveAlerts() {
        return new ArrayList<>(activeAlerts.values());
    }

    public void autoDismissForOrder(long orderId) {
        activeAlerts.entrySet().removeIf(entry -> {
            BlockingAlert a = entry.getValue();
            if (a.orderId() != null && a.orderId() == orderId) {
                hub.publish("blocking-alert-dismiss", entry.getKey());
                log.info("Alerta bloqueante [{}] auto-descartada por resolución de orden #{}", entry.getKey(), orderId);
                return true;
            }
            return false;
        });
    }

    public void autoDismissForSale(long saleId) {
        activeAlerts.entrySet().removeIf(entry -> {
            BlockingAlert a = entry.getValue();
            if (a.saleId() != null && a.saleId() == saleId) {
                hub.publish("blocking-alert-dismiss", entry.getKey());
                log.info("Alerta bloqueante [{}] auto-descartada por éxito de impresión en venta #{}", entry.getKey(), saleId);
                return true;
            }
            return false;
        });
    }
}

