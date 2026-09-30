package com.fruver.domain.service;

import com.fruver.domain.enums.ReviewStatus;
import java.math.BigDecimal;
import java.util.List;

/** Semaforo deterministico. El LLM aporta datos; nunca decide el color (y solo puede empeorarlo). */
public final class ReviewEvaluator {

    public static final BigDecimal MAX_REASONABLE_QTY = new BigDecimal("200");

    public ReviewStatus evaluate(List<ItemAssessment> items, boolean contradictionDetected) {
        if (items == null || items.isEmpty() || contradictionDetected) return ReviewStatus.RED;
        ReviewStatus result = ReviewStatus.GREEN;
        for (ItemAssessment i : items) {
            ReviewStatus s = evaluateItem(i);
            if (s == ReviewStatus.RED) return ReviewStatus.RED;
            if (s == ReviewStatus.YELLOW) result = ReviewStatus.YELLOW;
        }
        return result;
    }

    private ReviewStatus evaluateItem(ItemAssessment i) {
        boolean qtyMissing = i.quantity() == null || i.quantity().signum() <= 0;
        if (i.productMatches() == 0 || qtyMissing || i.unit() == null) return ReviewStatus.RED;
        if (i.quantity().compareTo(MAX_REASONABLE_QTY) > 0) return ReviewStatus.YELLOW;
        if (i.productMatches() > 1 || i.aiFlaggedAmbiguous() || !i.productResolved()) return ReviewStatus.YELLOW;
        return ReviewStatus.GREEN;
    }
}
