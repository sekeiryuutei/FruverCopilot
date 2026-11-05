package com.fruver.application;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.service.OrderChunker;
import com.fruver.domain.enums.ConversationAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderExtractorChunkingTest {

    @Test
    @DisplayName("shouldChunk detecta correctamente pedidos que superan el umbral de líneas")
    void testCountLinesAndShouldChunk() {
        String shortText = "1 kilo de papa\n2 kilos de cebolla";
        assertEquals(2, OrderChunker.countLines(shortText));
        assertFalse(OrderChunker.shouldChunk(shortText, 25));

        StringBuilder longBuilder = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            longBuilder.append("Producto ").append(i).append(" x ").append(i).append(" kg\n");
        }
        String longText = longBuilder.toString();
        assertEquals(30, OrderChunker.countLines(longText));
        assertTrue(OrderChunker.shouldChunk(longText, 25));
    }

    @Test
    @DisplayName("splitText divide un texto largo en bloques del tamaño especificado sin perder líneas")
    void testSplitText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 65; i++) {
            sb.append("Item ").append(i).append("\n");
        }

        List<String> chunks = OrderChunker.splitText(sb.toString(), 20);
        // 65 items / 20 = 4 chunks (20, 20, 20, 5)
        assertEquals(4, chunks.size());
        assertEquals(20, OrderChunker.countLines(chunks.get(0)));
        assertEquals(20, OrderChunker.countLines(chunks.get(1)));
        assertEquals(20, OrderChunker.countLines(chunks.get(2)));
        assertEquals(5, OrderChunker.countLines(chunks.get(3)));
        assertTrue(chunks.get(0).startsWith("Item 1"));
        assertTrue(chunks.get(3).endsWith("Item 65"));
    }

    @Test
    @DisplayName("splitInHalf divide un texto en dos partes equilibradas")
    void testSplitInHalf() {
        String text = "L1\nL2\nL3\nL4\nL5\nL6";
        List<String> halves = OrderChunker.splitInHalf(text);
        assertEquals(2, halves.size());
        assertEquals(3, OrderChunker.countLines(halves.get(0)));
        assertEquals(3, OrderChunker.countLines(halves.get(1)));
    }

    @Test
    @DisplayName("mergeResponses consolida los items de múltiples sub-lotes manteniendo el orden y metadatos")
    void testMergeResponses() {
        AIOrderResponse chunk1 = new AIOrderResponse(
                List.of(new ItemSugeridoDTO("papa pastusa", "Papa", new BigDecimal("10"), "KG", false, null)),
                ConversationAction.CONFIRM_ORDER,
                "Buen día",
                null,
                "Calle 10 # 5-20",
                "8:00 AM",
                "Restaurante El Sabor",
                "Transferencia"
        );

        AIOrderResponse chunk2 = new AIOrderResponse(
                List.of(new ItemSugeridoDTO("tomate chonto", "Tomate", new BigDecimal("5"), "KG", true, "dudoso")),
                ConversationAction.CONFIRM_ORDER,
                null,
                "Por favor confirmar si tomate maduro",
                null,
                null,
                null,
                null
        );

        AIOrderResponse merged = OrderChunker.mergeResponses(List.of(chunk1, chunk2));

        assertNotNull(merged);
        assertEquals(2, merged.items().size());
        assertEquals("Papa", merged.items().get(0).productoNormalizado());
        assertEquals("Tomate", merged.items().get(1).productoNormalizado());
        assertEquals("Restaurante El Sabor", merged.nombreCliente());
        assertEquals("Calle 10 # 5-20", merged.direccion());
        assertEquals("8:00 AM", merged.horarioEntrega());
        assertEquals("Transferencia", merged.metodoPago());
        assertTrue(merged.clarificationReason().contains("Por favor confirmar si tomate maduro"));
    }

    @Test
    @DisplayName("Simulación de pedido de 100 productos: se procesa en chunks y se fusiona en un solo resultado completo")
    void testLargeOrderMapReduceSimulation() {
        // Generar pedido de 100 productos
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append(i).append(" kilos de producto ").append(i).append("\n");
        }
        String message100 = sb.toString();

        assertTrue(OrderChunker.shouldChunk(message100, 25));
        List<String> chunks = OrderChunker.splitText(message100, 20);
        assertEquals(5, chunks.size()); // 5 sub-lotes de 20

        List<AIOrderResponse> subResponses = new ArrayList<>();
        for (int c = 0; c < chunks.size(); c++) {
            List<ItemSugeridoDTO> chunkItems = new ArrayList<>();
            int startIdx = c * 20 + 1;
            for (int j = 0; j < 20; j++) {
                int itemNum = startIdx + j;
                chunkItems.add(new ItemSugeridoDTO("prod " + itemNum, "Prod " + itemNum, new BigDecimal(itemNum), "KG", false, null));
            }
            subResponses.add(new AIOrderResponse(chunkItems, ConversationAction.CONFIRM_ORDER, null, null, "Bodega 4", null, "Cliente Mayorista", null));
        }

        AIOrderResponse finalResponse = OrderChunker.mergeResponses(subResponses);
        assertEquals(100, finalResponse.items().size());
        assertEquals("Prod 1", finalResponse.items().get(0).productoNormalizado());
        assertEquals("Prod 100", finalResponse.items().get(99).productoNormalizado());
        assertEquals("Cliente Mayorista", finalResponse.nombreCliente());
        assertEquals("Bodega 4", finalResponse.direccion());
    }
}
