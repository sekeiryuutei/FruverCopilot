package com.fruver.application;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ExtractionRequest;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.infrastructure.ai.MockOrderExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MockOrderExtractorTest {

    private final MockOrderExtractor mock = new MockOrderExtractor();

    private AIOrderResponse run(String msg, List<String> current) {
        return mock.extract(new ExtractionRequest(msg, List.of(), current, List.of()));
    }

    @Test void defaultReturnsPapaAndTomato() {
        AIOrderResponse r = run("cualquier cosa", List.of());
        assertEquals(2, r.items().size());
        assertEquals("Papa criolla", r.items().get(0).productoNormalizado());
        assertEquals(ConversationAction.CONFIRM_ORDER, r.action());
    }
    @Test void ambiguousKeywordAsksTheCustomer() {
        AIOrderResponse r = run("Simular AMBIGUO por favor", List.of());
        assertEquals(ConversationAction.ASK_CUSTOMER, r.action());
        assertTrue(r.items().get(0).ambiguo());
    }
    @Test void errorKeywordThrowsTechnicalError() {
        assertThrows(ExtractionUnavailableException.class, () -> run("simular error", List.of()));
    }
    @Test void customerReplyClosesTheClarificationLoop() {
        AIOrderResponse r = run("criolla", List.of("papa 5 LB"));
        assertEquals(1, r.items().size());
        assertEquals(ConversationAction.CONFIRM_ORDER, r.action());
    }
}
