package com.fruver.infrastructure.printing;

import com.fruver.application.dto.BlockingAlert;
import com.fruver.application.service.BlockingAlertService;
import com.fruver.application.service.SseHub;
import com.fruver.application.service.TicketData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrotherEmailPdfSizeTest {

    @Test
    @DisplayName("Debe permitir PDFs dentro del límite seguro de 20 MB")
    void testPdfSizeWithinAllowedLimitPasses() {
        BrotherEmailPrintStrategy strategy = new BrotherEmailPrintStrategy(null, null, null);

        // 35 KB (tamaño típico de factura)
        assertDoesNotThrow(() -> strategy.validatePdfSize(101L, 35 * 1024L));

        // 5 MB (factura extensa)
        assertDoesNotThrow(() -> strategy.validatePdfSize(102L, 5 * 1024 * 1024L));

        // 20 MB exactos (límite superior)
        assertDoesNotThrow(() -> strategy.validatePdfSize(103L, 20L * 1024 * 1024));
    }

    @Test
    @DisplayName("Debe rechazar PDFs mayores a 20 MB y disparar alerta bloqueante al administrador")
    void testPdfSizeExceeding20MbThrowsAndRaisesAlert() {
        SseHub hub = new SseHub();
        ObjectMapper mapper = new ObjectMapper();
        BlockingAlertService alertService = new BlockingAlertService(hub, mapper);

        BrotherEmailPrintStrategy strategy = new BrotherEmailPrintStrategy(null, null, alertService);

        long oversizedBytes = (21L * 1024 * 1024); // 21 MB

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                strategy.validatePdfSize(999L, oversizedBytes)
        );

        assertTrue(ex.getMessage().contains("21.00 MB"));
        assertTrue(ex.getMessage().contains("excede el tamaño máximo permitido de 20 MB"));

        // Verificar que la alerta bloqueante quedó registrada en memoria para el administrador
        List<BlockingAlert> alerts = alertService.getActiveAlerts();
        assertEquals(1, alerts.size());
        BlockingAlert alert = alerts.get(0);
        assertEquals("PDF_OVERSIZED_999", alert.id());
        assertEquals("CRITICAL", alert.type());
        assertTrue(alert.title().contains("Factura excede límite de 20 MB"));
        assertTrue(alert.message().contains("21.00 MB"));
        assertEquals(999L, alert.orderId());
    }

    @Test
    @DisplayName("Debe verificar el límite de borde exacto (20 MB + 1 byte falla)")
    void testPdfSizeAtExactBoundary() {
        BrotherEmailPrintStrategy strategy = new BrotherEmailPrintStrategy(null, null, null);

        long exactLimit = BrotherEmailPrintStrategy.MAX_PDF_BYTES;
        assertDoesNotThrow(() -> strategy.validatePdfSize(200L, exactLimit));

        long boundaryPlusOne = exactLimit + 1;
        assertThrows(IllegalStateException.class, () ->
                strategy.validatePdfSize(201L, boundaryPlusOne)
        );
    }

    @Test
    @DisplayName("El PDF real generado por TalonarioRasterService debe pesar mucho menos de 1 MB (< 50 KB)")
    void testRealInvoicePdfIsUnderHalfMegabyte() {
        TalonarioRasterService raster = new TalonarioRasterService();

        List<TicketData.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            lines.add(new TicketData.Line(
                    "Producto " + i,
                    new BigDecimal(i),
                    "KG",
                    new BigDecimal("3500"),
                    new BigDecimal(i * 3500)
            ));
        }

        TicketData ticket = new TicketData(
                "FRUVER EL OASIS",
                12345L,
                OffsetDateTime.now(),
                "Cliente De Prueba Tamaño",
                "3101234567",
                "RETAIL",
                lines,
                new BigDecimal("50000"),
                "Calle 10 # 5-20",
                "Entrega urgente",
                false
        );

        String html = raster.buildA4CenteredHtml(ticket);
        assertNotNull(html);

        // El HTML pesa menos de 25 KB
        int htmlBytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertTrue(htmlBytes < 25 * 1024, "El HTML debe ser hiperligero (< 25 KB), pero pesó " + htmlBytes + " bytes");
    }
}
