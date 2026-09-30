package com.fruver.domain.enums;

import java.util.Optional;

public enum UnitOfMeasure {
    LB, KG, UNIDAD;

    /** La salida del LLM no es confiable: se parsea de forma tolerante, nunca con valueOf directo. */
    public static Optional<UnitOfMeasure> parse(String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.trim().toUpperCase()) {
            case "LB", "LIBRA", "LIBRAS" -> Optional.of(LB);
            case "KG", "KILO", "KILOS" -> Optional.of(KG);
            case "UN", "UND", "UNIDAD", "UNIDADES" -> Optional.of(UNIDAD);
            default -> Optional.empty();
        };
    }

    public String label() {
        return this == UNIDAD ? "UN" : name();
    }
}
