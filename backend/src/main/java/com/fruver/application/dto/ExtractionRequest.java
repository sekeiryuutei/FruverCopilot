package com.fruver.application.dto;

import java.util.List;

/** Contexto minimo para el LLM: mensaje, ultimos turnos, pedido actual, candidatos de catalogo y zonas de entrega. */
public record ExtractionRequest(
        String message,
        List<String> recentTurns,
        List<String> currentItems,
        List<String> catalogCandidates,
        String deliveryZones) {

    public ExtractionRequest(String message, List<String> recentTurns, List<String> currentItems, List<String> catalogCandidates) {
        this(message, recentTurns, currentItems, catalogCandidates, null);
    }
}
