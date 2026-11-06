package com.fruver.application;

import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.service.ExtractionInterpreter;
import com.fruver.application.service.SaleService;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.domain.service.ItemAssessment;
import com.fruver.domain.service.ReviewEvaluator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomerAccuracyRegressionTest {

    private final ReviewEvaluator evaluator = new ReviewEvaluator();

    @Test
    void testMilanoNotInterpretedAsMoney() {
        // "2 MILANO" no debe interpretarse como dinero ($2.000) por contener "mil"
        ItemSugeridoDTO milano = new ItemSugeridoDTO("2 MILANO", "Tomate Milano", new BigDecimal("2"), null, false, null);
        ExtractionInterpreter interpreter = new ExtractionInterpreter(item -> new com.fruver.application.port.ProductLookup.Resolution(1L, 1, "LB"));
        var interpreted = interpreter.interpret(new com.fruver.application.dto.AIOrderResponse(List.of(milano), null, null, null));

        assertEquals(1, interpreted.items().size());
        var it = interpreted.items().get(0);
        assertNull(it.manualPrice(), "2 MILANO nunca debe tener manualPrice por contener 'mil'");
        assertEquals(new BigDecimal("2.000"), it.quantity());
    }

    @Test
    void testRealMoneyLineIsInterpretedAsMoney() {
        // "1000 de cilantro" sí es pedido por valor
        ItemSugeridoDTO cilantro = new ItemSugeridoDTO("1000 de cilantro", "Cilantro", new BigDecimal("1000"), "VALOR", false, "Pedido por valor: $1000");
        ExtractionInterpreter interpreter = new ExtractionInterpreter(item -> new com.fruver.application.port.ProductLookup.Resolution(2L, 1, "ATADO"));
        var interpreted = interpreter.interpret(new com.fruver.application.dto.AIOrderResponse(List.of(cilantro), null, null, null));

        assertEquals(1, interpreted.items().size());
        var it = interpreted.items().get(0);
        assertNotNull(it.manualPrice());
        assertEquals(new BigDecimal("1000"), it.manualPrice());
        assertNull(it.quantity(), "Pedidos en dinero no tienen cantidad fija");
    }

    @Test
    void testUnitLIsRecognizedAndValid() {
        var parsed = UnitOfMeasure.parse("L");
        assertTrue(parsed.isPresent(), "Unidad 'L' debe ser reconocida");
        assertEquals(UnitOfMeasure.L, parsed.get());
        assertEquals("L", parsed.get().label());

        var parsedDot = UnitOfMeasure.parse("l.");
        assertTrue(parsedDot.isPresent());
        assertEquals(UnitOfMeasure.L, parsedDot.get());
    }

    @Test
    void testUnitLDoesNotTriggerRedReviewStatus() {
        ItemAssessment assessment = new ItemAssessment(true, 1, new BigDecimal("2"), UnitOfMeasure.L, false, false, false);
        ReviewStatus status = evaluator.evaluate(List.of(assessment), false);
        assertEquals(ReviewStatus.GREEN, status, "Unidad L válida no debe degradar a RED ni YELLOW");
    }

    @Test
    void testSaleServiceFiltersTechnicalDiagnosticsFromPrintedTicket() {
        // Notas técnicas que NO deben salir en la comanda
        assertNull(SaleService.extractItemNote("Cantidad sin unidad explícita"));
        assertNull(SaleService.extractItemNote("Pedido en libras"));
        assertNull(SaleService.extractItemNote("La L se interpreta como libras"));
        assertNull(SaleService.extractItemNote("Nota de voz adicional"));
        assertNull(SaleService.extractItemNote("Unidad no especificada en el renglón"));
        assertNull(SaleService.extractItemNote("Fracción mixta 7 1/2"));

        // Notas gastronómicas reales del cliente que SÍ deben salir
        assertEquals("bien maduro", SaleService.extractItemNote("Nota: bien maduro"));
        assertEquals("hoja verde y fresca", SaleService.extractItemNote("Nota: hoja verde y fresca"));

        // Comprobación de armado de nombre para comanda
        assertEquals("Papa Parda", SaleService.buildProductNameWithNote("Papa Parda", "Cantidad sin unidad explícita"));
        assertEquals("Plátano verde (bien maduro)", SaleService.buildProductNameWithNote("Plátano verde", "Nota: bien maduro"));
    }
}

