package com.fruver.application;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.InterpretedOrder;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.ProductLookup;
import com.fruver.application.service.ExtractionInterpreter;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** La salida del LLM es UNTRUSTED: estos tests prueban la validacion, no al modelo. */
class ExtractionInterpreterTest {

    private final ProductLookup lookup = d -> {
        String n = d.productoNormalizado() != null ? d.productoNormalizado() : d.productoMencionado();
        if ("Papa criolla".equalsIgnoreCase(n)) return new ProductLookup.Resolution(15L, 1);
        if ("Aguacate Hass".equalsIgnoreCase(n)) return new ProductLookup.Resolution(7L, 1);
        if ("papa".equalsIgnoreCase(n)) return new ProductLookup.Resolution(null, 3);
        return new ProductLookup.Resolution(null, 0);
    };
    private final ExtractionInterpreter interpreter = new ExtractionInterpreter(lookup);

    private static ItemSugeridoDTO item(String mentioned, String normalized, String qty, String unit, boolean ambiguous) {
        return new ItemSugeridoDTO(mentioned, normalized, qty == null ? null : new BigDecimal(qty), unit, ambiguous, null);
    }

    private InterpretedOrder run(ConversationAction action, String message, ItemSugeridoDTO... items) {
        return interpreter.interpret(new AIOrderResponse(List.of(items), action, message, "motivo"));
    }

    @Test void clearOrderIsGreenAndResolvedAgainstCatalog() {
        InterpretedOrder r = run(ConversationAction.CONFIRM_ORDER, null, item("papa criolla", "Papa criolla", "2", "libras", false));
        assertEquals(ReviewStatus.GREEN, r.review());
        assertEquals(15L, r.items().get(0).productId());
        assertEquals(UnitOfMeasure.LB, r.items().get(0).unit());
        assertEquals(ConversationAction.CONFIRM_ORDER, r.action());
    }
    @Test void ambiguousProductAsksCustomerWithAiQuestion() {
        InterpretedOrder r = run(ConversationAction.ASK_CUSTOMER, "¿Criolla, pastusa o sabanera?", item("papa", null, "5", "LB", true));
        assertEquals(ReviewStatus.YELLOW, r.review());
        assertNull(r.items().get(0).productId());
        assertEquals(ConversationAction.ASK_CUSTOMER, r.action());
        assertEquals("¿Criolla, pastusa o sabanera?", r.customerMessage());
    }
    @Test void missingQuantityIsRed() {
        assertEquals(ReviewStatus.RED, run(ConversationAction.NONE, null, item("papa criolla", "Papa criolla", null, "LB", false)).review());
    }
    @Test void monetaryValueIsNotAQuantity() {
        InterpretedOrder r = run(ConversationAction.ASK_CUSTOMER, null, item("5 mil de tomate", null, null, null, true));
        assertEquals(ReviewStatus.RED, r.review());
        assertNotNull(r.customerMessage()); // el backend genera la pregunta
    }
    @Test void promptInjectionQuantityNeverTurnsGreen() {
        assertEquals(ReviewStatus.YELLOW, run(ConversationAction.CONFIRM_ORDER, null, item("aguacates", "Aguacate Hass", "500", "UNIDAD", false)).review());
    }
    @Test void absurdQuantityIsDiscarded() {
        assertEquals(ReviewStatus.RED, run(null, null, item("papa criolla", "Papa criolla", "9999999", "LB", false)).review());
    }
    @Test void hallucinatedProductIsRed() {
        assertEquals(ReviewStatus.RED, run(null, null, item("mango", "Mango Tommy", "3", "KG", false)).review());
    }
    @Test void invalidUnitAndNegativeQuantityAreRed() {
        assertEquals(ReviewStatus.RED, run(null, null, item("papa criolla", "Papa criolla", "3", "arrobas", false)).review());
        assertEquals(ReviewStatus.RED, run(null, null, item("papa criolla", "Papa criolla", "-3", "LB", false)).review());
    }
    @Test void nullOrEmptyResponseGoesToOperator() {
        InterpretedOrder n = interpreter.interpret(null);
        assertEquals(ReviewStatus.RED, n.review());
        assertEquals(ConversationAction.WAIT_FOR_OPERATOR, n.action());
        assertEquals(ReviewStatus.RED, interpreter.interpret(new AIOrderResponse(List.of(), ConversationAction.NONE, null, null)).review());
        assertEquals(ReviewStatus.RED, interpreter.interpret(new AIOrderResponse(null, null, null, null)).review());
    }
    @Test void customerMessageWithPriceOrLinkIsDiscarded() {
        InterpretedOrder r = run(ConversationAction.ASK_CUSTOMER, "La libra cuesta $4.500", item("papa", null, "1", "LB", true));
        assertFalse(r.customerMessage().contains("$"));
        assertNotNull(run(ConversationAction.ASK_CUSTOMER, "mira http://malo.com", item("papa", null, "1", "LB", true)).customerMessage());
    }
    @Test void aiCannotDowngradeAmbiguity() {
        assertEquals(ReviewStatus.YELLOW, run(ConversationAction.CONFIRM_ORDER, null, item("papa criolla", "Papa criolla", "3", "LB", true)).review());
    }
    @Test void rawAiQuantityThatDoesNotFitTheColumnIsDropped() {
        InterpretedOrder r = run(null, null, item("papa criolla", "Papa criolla", "99999999999", "LB", false));
        assertNull(r.items().get(0).aiQuantity());
        assertEquals(ReviewStatus.RED, r.review());
    }
    @Test void zeroAndNegativeQuantitiesNeverReachTheOrder() {
        assertNull(run(null, null, item("papa criolla", "Papa criolla", "0", "LB", false)).items().get(0).quantity());
        assertNull(run(null, null, item("papa criolla", "Papa criolla", "-9", "LB", false)).items().get(0).quantity());
    }
    @Test void nullItemsInsideListAreIgnored() {
        InterpretedOrder r = interpreter.interpret(new AIOrderResponse(java.util.Arrays.asList(null, item("papa criolla", "Papa criolla", "1", "LB", false)), null, null, null));
        assertEquals(1, r.items().size());
    }
}
