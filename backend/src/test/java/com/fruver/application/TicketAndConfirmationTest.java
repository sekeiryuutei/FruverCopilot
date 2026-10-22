package com.fruver.application;

import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.application.service.ConfirmationMessageBuilder;
import com.fruver.application.service.TicketData;
import com.fruver.application.service.TicketFormatter;
import com.fruver.infrastructure.printing.EscPosEncoder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TicketAndConfirmationTest {

    private TicketData data(boolean reprint) {
        return new TicketData("FRUVER", 10542, OffsetDateTime.parse("2026-09-28T15:42:00-05:00"), "María Pérez", "3001234567", "WHOLESALE",
                List.of(new TicketData.Line("Papa criolla", new BigDecimal("3"), "LB", new BigDecimal("4500"), new BigDecimal("13500")),
                        new TicketData.Line("Aguacate Hass", new BigDecimal("4"), "UNIDAD", new BigDecimal("2500"), new BigDecimal("10000"))),
                new BigDecimal("23500"), "Cra 5 # 10-20 apto 301, barrio San Fernando", "Sin cilantro", reprint);
    }

    @Test void ticketContainsRequiredFieldsAndFitsPaperWidth() {
        for (int cols : new int[]{32, 42}) {
            var lines = TicketFormatter.format(data(false), cols);
            String all = String.join("\n", lines.stream().map(TicketFormatter.TicketLine::text).toList());
            assertTrue(all.contains("FACTURA #10542") || all.contains("PEDIDO #10542"));
            assertTrue(all.contains("MAYORISTA"));
            assertTrue(all.contains("TOTAL"));
            assertTrue(all.contains("DIRECCION") && all.contains("OBS: Sin cilantro"));
            assertTrue(all.lines().allMatch(l -> l.length() <= cols), "linea excede " + cols + " columnas");
        }
    }
    @Test void reprintIsMarked() {
        assertTrue(TicketFormatter.format(data(true), 32).get(0).text().contains("REIMPRESION"));
    }
    @Test void escPosStartsWithInitEndsWithCutAndIsAscii() {
        byte[] b = EscPosEncoder.encode(TicketFormatter.format(data(false), 42));
        assertEquals(0x1B, b[0]); assertEquals(0x40, b[1]);
        assertEquals(0x1D, b[b.length - 4]); assertEquals(0x56, b[b.length - 3]);
        assertTrue(new String(b, StandardCharsets.ISO_8859_1).chars().allMatch(c -> c < 128));
    }
    @Test void confirmationUsesBackendTotalAndRefusesIncompleteQuotes() {
        Quote q = new Quote(List.of(new QuoteLine(1L, 15L, "Papa criolla", new BigDecimal("3"), "LB", new BigDecimal("4500"),
                new BigDecimal("4500"), false, new BigDecimal("13500"), null)), new BigDecimal("13500"), true, "RETAIL");
        String msg = ConfirmationMessageBuilder.build(q);
        assertTrue(msg.contains("3 libras de Papa criolla") && msg.contains("$13.500"));
        assertNull(ConfirmationMessageBuilder.build(new Quote(List.of(), BigDecimal.ZERO, false, "RETAIL")));
    }
}
