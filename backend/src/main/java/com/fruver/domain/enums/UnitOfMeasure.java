package com.fruver.domain.enums;

import java.util.Optional;

public enum UnitOfMeasure {
    LB, KG, UNIDAD, CAJA, BULTO, ARROBA, ATADO, BOLSA, PAQUETE, MANOJO, L;

    /** La salida del LLM no es confiable: se parsea de forma tolerante, nunca con valueOf directo. */
    public static Optional<UnitOfMeasure> parse(String raw) {
        if (raw == null) return Optional.empty();
        return switch (raw.trim().toUpperCase()) {
            case "LB", "LIBRA", "LIBRAS", "LBS" -> Optional.of(LB);
            case "KG", "KILO", "KILOS", "KL", "KLS", "KL.", "K" -> Optional.of(KG);
            case "UN", "UND", "UNIDAD", "UNIDADES" -> Optional.of(UNIDAD);
            case "CAJA", "CAJAS", "CJ" -> Optional.of(CAJA);
            case "BULTO", "BULTOS", "BL" -> Optional.of(BULTO);
            case "ARROBA", "ARROBAS", "@" -> Optional.of(ARROBA);
            case "ATADO", "ATADOS" -> Optional.of(ATADO);
            case "BOLSA", "BOLSAS" -> Optional.of(BOLSA);
            case "PAQUETE", "PAQUETES", "PQ" -> Optional.of(PAQUETE);
            case "MANOJO", "MANOJOS" -> Optional.of(MANOJO);
            case "L", "L." -> Optional.of(L);
            default -> Optional.empty();
        };
    }

    public String label() {
        return switch (this) {
            case UNIDAD -> "UN";
            case CAJA -> "CJ";
            case BULTO -> "BL";
            case ARROBA -> "@";
            case ATADO -> "AT";
            case BOLSA -> "BOL";
            case PAQUETE -> "PQ";
            case MANOJO -> "MJ";
            case L -> "L";
            default -> name();
        };
    }
}
