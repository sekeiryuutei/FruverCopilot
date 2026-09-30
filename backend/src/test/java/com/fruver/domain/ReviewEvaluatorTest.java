package com.fruver.domain;

import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.domain.service.ItemAssessment;
import com.fruver.domain.service.ReviewEvaluator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewEvaluatorTest {

    private final ReviewEvaluator evaluator = new ReviewEvaluator();

    private ItemAssessment item(int matches, String qty, UnitOfMeasure unit, boolean aiAmbiguous) {
        return new ItemAssessment(matches == 1, matches, qty == null ? null : new BigDecimal(qty), unit, aiAmbiguous);
    }

    @Test void clearItemIsGreen() {
        assertEquals(ReviewStatus.GREEN, evaluator.evaluate(List.of(item(1, "2", UnitOfMeasure.LB, false)), false));
    }
    @Test void multipleCatalogMatchesIsYellow() {
        assertEquals(ReviewStatus.YELLOW, evaluator.evaluate(List.of(item(3, "3", UnitOfMeasure.LB, false)), false));
    }
    @Test void missingQuantityIsRed() {
        assertEquals(ReviewStatus.RED, evaluator.evaluate(List.of(item(1, null, UnitOfMeasure.LB, false)), false));
    }
    @Test void unknownProductIsRed() {
        assertEquals(ReviewStatus.RED, evaluator.evaluate(List.of(item(0, "2", UnitOfMeasure.LB, false)), false));
    }
    @Test void emptyExtractionIsRed() {
        assertEquals(ReviewStatus.RED, evaluator.evaluate(List.of(), false));
    }
    @Test void contradictionIsRed() {
        assertEquals(ReviewStatus.RED, evaluator.evaluate(List.of(item(1, "2", UnitOfMeasure.LB, false)), true));
    }
    @Test void absurdQuantityIsYellowNotGreen() {
        assertEquals(ReviewStatus.YELLOW, evaluator.evaluate(List.of(item(1, "500", UnitOfMeasure.UNIDAD, false)), false));
    }
    @Test void aiCanRaiseButNeverLowerTheLight() {
        assertEquals(ReviewStatus.YELLOW, evaluator.evaluate(List.of(item(1, "2", UnitOfMeasure.LB, true)), false));
    }
    @Test void worstItemWins() {
        assertEquals(ReviewStatus.RED, evaluator.evaluate(List.of(item(1, "2", UnitOfMeasure.LB, false), item(0, "1", UnitOfMeasure.KG, false)), false));
    }
}
