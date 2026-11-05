package com.fruver.application.service;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.domain.enums.ConversationAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Estrategia Map-Reduce en Chunks para procesamiento seguro de pedidos grandes.
 * Evita caídas por timeout cuando los clientes envían listas de 30 a 100+ productos.
 */
public final class OrderChunker {

    public static final int DEFAULT_THRESHOLD_LINES = 25;
    public static final int DEFAULT_CHUNK_SIZE = 20;

    private OrderChunker() {}

    public static int countLines(String text) {
        if (text == null || text.isBlank()) return 0;
        String[] lines = text.split("\\r?\\n");
        int count = 0;
        for (String l : lines) {
            if (!l.trim().isEmpty()) count++;
        }
        return count;
    }

    public static boolean shouldChunk(String text, int threshold) {
        return countLines(text) >= threshold;
    }

    public static List<String> splitText(String text, int chunkSize) {
        if (text == null || text.isBlank()) return List.of();
        String[] rawLines = text.split("\\r?\\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String l : rawLines) {
            String trimmed = l.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }

        if (nonEmpty.size() <= chunkSize) {
            return List.of(String.join("\n", nonEmpty));
        }

        List<String> chunks = new ArrayList<>();
        int currentSize = Math.max(5, chunkSize);
        for (int i = 0; i < nonEmpty.size(); i += currentSize) {
            int end = Math.min(nonEmpty.size(), i + currentSize);
            List<String> slice = nonEmpty.subList(i, end);
            chunks.add(String.join("\n", slice));
        }
        return chunks;
    }

    public static List<String> splitInHalf(String text) {
        if (text == null || text.isBlank()) return List.of();
        String[] rawLines = text.split("\\r?\\n");
        List<String> nonEmpty = new ArrayList<>();
        for (String l : rawLines) {
            String trimmed = l.trim();
            if (!trimmed.isEmpty()) {
                nonEmpty.add(trimmed);
            }
        }
        if (nonEmpty.size() <= 1) {
            return List.of(text);
        }
        int mid = nonEmpty.size() / 2;
        String first = String.join("\n", nonEmpty.subList(0, mid));
        String second = String.join("\n", nonEmpty.subList(mid, nonEmpty.size()));
        return List.of(first, second);
    }

    public static AIOrderResponse mergeResponses(List<AIOrderResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return new AIOrderResponse(List.of(), ConversationAction.CONFIRM_ORDER, null, null);
        }

        List<ItemSugeridoDTO> combinedItems = new ArrayList<>();
        String customerMessage = null;
        StringBuilder clarification = new StringBuilder();
        String direccion = null;
        String horarioEntrega = null;
        String nombreCliente = null;
        String metodoPago = null;
        ConversationAction action = ConversationAction.CONFIRM_ORDER;

        for (AIOrderResponse r : responses) {
            if (r == null) continue;
            if (r.items() != null) {
                combinedItems.addAll(r.items());
            }
            if (r.action() == ConversationAction.ASK_CUSTOMER) {
                action = ConversationAction.ASK_CUSTOMER;
            }
            if (customerMessage == null && r.customerMessage() != null && !r.customerMessage().isBlank()) {
                customerMessage = r.customerMessage();
            }
            if (r.clarificationReason() != null && !r.clarificationReason().isBlank()) {
                if (!clarification.isEmpty()) clarification.append("; ");
                clarification.append(r.clarificationReason());
            }
            if (direccion == null && r.direccion() != null && !r.direccion().isBlank()) {
                direccion = r.direccion();
            }
            if (horarioEntrega == null && r.horarioEntrega() != null && !r.horarioEntrega().isBlank()) {
                horarioEntrega = r.horarioEntrega();
            }
            if (nombreCliente == null && r.nombreCliente() != null && !r.nombreCliente().isBlank()) {
                nombreCliente = r.nombreCliente();
            }
            if (metodoPago == null && r.metodoPago() != null && !r.metodoPago().isBlank()) {
                metodoPago = r.metodoPago();
            }
        }

        String clar = clarification.isEmpty() ? null : clarification.toString();
        return new AIOrderResponse(combinedItems, action, customerMessage, clar, direccion, horarioEntrega, nombreCliente, metodoPago);
    }
}
