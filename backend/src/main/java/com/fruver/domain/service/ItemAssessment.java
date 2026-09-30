package com.fruver.domain.service;

import com.fruver.domain.enums.UnitOfMeasure;
import java.math.BigDecimal;

/** Lo que ReviewEvaluator necesita saber de un item, sin depender de persistencia ni de IA. */
public record ItemAssessment(
        boolean productResolved,
        int productMatches,
        BigDecimal quantity,
        UnitOfMeasure unit,
        boolean aiFlaggedAmbiguous) {}
