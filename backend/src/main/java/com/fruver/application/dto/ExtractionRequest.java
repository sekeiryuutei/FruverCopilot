package com.fruver.application.dto;

import java.util.List;

/** Contexto minimo para el LLM: mensaje, ultimos turnos, pedido actual, candidatos de catalogo, zonas de entrega e historial del cliente. */
public record ExtractionRequest(
        String message,
        List<String> recentTurns,
        List<String> currentItems,
        List<String> catalogCandidates,
        String deliveryZones,
        String customerHistory) {

    public ExtractionRequest(String message, List<String> recentTurns, List<String> currentItems, List<String> catalogCandidates) {
        this(message, recentTurns, currentItems, catalogCandidates, null, null);
    }

    public ExtractionRequest(String message, List<String> recentTurns, List<String> currentItems, List<String> catalogCandidates, String deliveryZones) {
        this(message, recentTurns, currentItems, catalogCandidates, deliveryZones, null);
    }
}
