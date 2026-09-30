package com.fruver.application.dto;

import java.util.List;

/** Contexto minimo para el LLM: mensaje, ultimos turnos, pedido actual y candidatos de catalogo. */
public record ExtractionRequest(
        String message,
        List<String> recentTurns,
        List<String> currentItems,
        List<String> catalogCandidates) {}
