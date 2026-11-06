package com.fruver.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.dto.BlockingAlert;
import com.fruver.application.service.BlockingAlertService;
import com.fruver.application.service.SseHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockingAlertServiceTest {

    private final SseHub hub = new SseHub();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final BlockingAlertService alertService = new BlockingAlertService(hub, mapper);

    @Test
    @DisplayName("raise() registra una alerta bloqueante y getActiveAlerts() la retorna")
    void testRaiseAndGetAlerts() {
        BlockingAlert alert = alertService.raise("ORDER_493", "CRITICAL",
                "Fallo al facturar Pedido #493",
                "Nombre excede 120 caracteres", 493L, null);

        assertNotNull(alert);
        assertEquals("ORDER_493", alert.id());
        assertEquals("CRITICAL", alert.type());
        assertEquals(493L, alert.orderId());

        List<BlockingAlert> list = alertService.getActiveAlerts();
        assertEquals(1, list.size());
        assertEquals("ORDER_493", list.get(0).id());
    }

    @Test
    @DisplayName("dismiss() remueve la alerta manualmente")
    void testDismissAlert() {
        alertService.raise("ALERT_TEST", "WARNING", "Alerta temporal", "Detalle", null, null);
        assertEquals(1, alertService.getActiveAlerts().size());

        boolean dismissed = alertService.dismiss("ALERT_TEST");
        assertTrue(dismissed);
        assertTrue(alertService.getActiveAlerts().isEmpty());
    }

    @Test
    @DisplayName("autoDismissForOrder() retira alertas asociadas cuando el pedido se resuelve")
    void testAutoDismissForOrder() {
        alertService.raise("ORDER_APPROVAL_123", "CRITICAL", "Error orden 123", "Detalle", 123L, null);
        alertService.raise("ORDER_APPROVAL_456", "CRITICAL", "Error orden 456", "Detalle", 456L, null);
        assertEquals(2, alertService.getActiveAlerts().size());

        alertService.autoDismissForOrder(123L);

        List<BlockingAlert> remaining = alertService.getActiveAlerts();
        assertEquals(1, remaining.size());
        assertEquals("ORDER_APPROVAL_456", remaining.get(0).id());
    }

    @Test
    @DisplayName("autoDismissForSale() retira alertas cuando la impresión de una venta se completa")
    void testAutoDismissForSale() {
        alertService.raise("PRINT_FAILED_800", "CRITICAL", "Error venta 800", "Detalle", null, 800L);
        assertEquals(1, alertService.getActiveAlerts().size());

        alertService.autoDismissForSale(800L);
        assertTrue(alertService.getActiveAlerts().isEmpty());
    }
}
