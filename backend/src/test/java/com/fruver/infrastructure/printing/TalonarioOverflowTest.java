package com.fruver.infrastructure.printing;

import com.fruver.application.service.SaleService;
import com.fruver.application.service.TicketData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Suite de pruebas de desbordamiento de texto, emojis y tablas para impresión")
class TalonarioOverflowTest {

    private final TalonarioRasterService rasterService = new TalonarioRasterService();

    @Test
    @DisplayName("sanitizeText remueve emojis y caracteres no estándar pero conserva tildes, números y letras")
    void testSanitizeTextRemovesEmojis() {
        assertEquals("Briyed Molina", TalonarioRasterService.sanitizeText("Briyed Molina 🦋🧠"));
        assertEquals("Gladis Possu", TalonarioRasterService.sanitizeText("Gladis Possu 👧🏻"));
        assertEquals("Papa criolla limpia", TalonarioRasterService.sanitizeText("Papa criolla limpia 🥔✨"));
        assertEquals("Limón Tahití 100% fresco", TalonarioRasterService.sanitizeText("Limón Tahití 100% fresco 🍋"));
        assertEquals("", TalonarioRasterService.sanitizeText(null));
    }

    @Test
    @DisplayName("extractItemNote filtra meta-razonamientos de IA y preserva notas reales del cliente")
    void testExtractItemNoteFiltersAiReasoning() {
        // Meta-razonamientos de IA deben ser descartados:
        assertNull(SaleService.extractItemNote("Tomado de la lista manuscrita (3 bultos)"));
        assertNull(SaleService.extractItemNote("Del pedido actual / historial frecuente"));
        assertNull(SaleService.extractItemNote("Mencionado en la nota adicional del cliente (bombillos interpretado...)"));
        assertNull(SaleService.extractItemNote("interpretado como producto adicional"));

        // Notas reales de producto deben preservarse:
        assertEquals("maduro para hoy", SaleService.extractItemNote("nota: maduro para hoy"));
        assertEquals("bien verde", SaleService.extractItemNote("bien verde"));
        assertEquals("para jugo", SaleService.extractItemNote("para jugo"));
    }

    @Test
    @DisplayName("buildProductNameWithNote recorta nombres excesivos a 45 caracteres y evita pegar razonamientos de IA")
    void testBuildProductNameWithNoteCapAndClean() {
        // Renglón de Wiliam Hermano: no debe pegar el texto de la libreta
        String cleanName = SaleService.buildProductNameWithNote("3 bultos", "Tomado de la lista manuscrita (3 bultos)");
        assertEquals("3 bultos", cleanName);

        String bolsasClean = SaleService.buildProductNameWithNote("Bolsas", "Mencionado en la nota adicional del cliente");
        assertEquals("Bolsas", bolsasClean);

        // Texto largo de 100 caracteres se recorta a 45 caracteres máximo
        String veryLong = "Manzana verde importada seleccionada extra grande premium de exportacion para ensalada";
        String capped = SaleService.buildProductNameWithNote(veryLong, null);
        assertTrue(capped.length() <= 45);
        assertEquals(veryLong.substring(0, 45).trim(), capped);
    }

    @Test
    @DisplayName("buildA4CenteredHtml genera tabla con layout fijo y elipsis anti-desbordamiento")
    void testHtmlTableLayoutFixedAndEllipsis() {
        TicketData.Line longLine1 = new TicketData.Line("3 BULTOS (TOMADO DE LA LISTA MANUSCRITA (3 BULTOS DE PAPA PASTUSA GRANDE DE EXPORTACION))",
                new BigDecimal("3.000"), "BULTO", null, null);
        TicketData.Line longLine2 = new TicketData.Line("A".repeat(300),
                new BigDecimal("1.000"), "UNIDAD", new BigDecimal("5000"), new BigDecimal("5000"));

        TicketData ticket = new TicketData("FRUVER EL OASIS", 654L, OffsetDateTime.now(),
                "Wiliam Hermano 🦋🧠", "3105425241", "WHOLESALE",
                List.of(longLine1, longLine2), new BigDecimal("5000"), "Calle 10 # 20-30",
                "Nota muy larga: " + "X".repeat(120), false, null, false, null, "AUDIO + IMAGEN");

        String html = rasterService.buildA4CenteredHtml(ticket);

        // 1. Debe incluir table-layout:fixed
        assertTrue(html.contains("table-layout:fixed"), "La tabla debe tener table-layout:fixed");

        // 2. Debe incluir estilos de corte y elipsis en la celda de descripción
        assertTrue(html.contains("text-overflow:ellipsis"), "Debe tener text-overflow:ellipsis");
        assertTrue(html.contains("white-space:nowrap"), "Debe tener white-space:nowrap");
        assertTrue(html.contains("max-width:0"), "Debe tener max-width:0 para forzar el ancho de celda de tabla fija");

        // 3. Emojis del cliente deben haber sido purgados del HTML
        assertFalse(html.contains("🦋"), "No debe contener emojis de mariposa");
        assertFalse(html.contains("🧠"), "No debe contener emojis de cerebro");
        assertTrue(html.contains("WILIAM HERMANO"), "Debe contener el nombre limpio en mayúsculas");
    }
}
