package com.fruver.application.dto;

import java.math.BigDecimal;

/** Propuesta de la IA para un item. No contiene precios. Es entrada NO confiable. */
public record ItemSugeridoDTO(
        String productoMencionado,
        String productoNormalizado,
        BigDecimal cantidad,
        String unidadMedida,
        boolean ambiguo,
        String observacion) {}
