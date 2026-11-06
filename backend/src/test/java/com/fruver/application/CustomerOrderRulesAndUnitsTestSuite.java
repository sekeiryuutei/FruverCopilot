package com.fruver.application;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.InterpretedOrder;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.ProductLookup;
import com.fruver.application.service.ExtractionInterpreter;
import com.fruver.application.service.SaleService;
import com.fruver.application.service.TicketData;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.ReviewStatus;
import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.infrastructure.printing.TalonarioRasterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Suite integral de pruebas para blindar las 5+ reglas de pedido de los clientes,
 * prevenir errores de Kilos vs. Libras, garantizar pedidos por valor/precio real
 * y verificar el formato de impresión sin numeración de ítems.
 */
class CustomerOrderRulesAndUnitsTestSuite {

    private final ProductLookup lookup = d -> {
        String n = d.productoNormalizado() != null ? d.productoNormalizado() : d.productoMencionado();
        if (n == null) return new ProductLookup.Resolution(null, 0);
        String lower = n.toLowerCase();
        if (lower.contains("papa criolla") || lower.contains("amarilla")) return new ProductLookup.Resolution(15L, 1);
        if (lower.contains("papa pastusa") || lower.contains("papa parda") || lower.contains("papa")) return new ProductLookup.Resolution(1L, 1);
        if (lower.contains("tomate chonto") || lower.contains("chonto") || lower.contains("tomate")) return new ProductLookup.Resolution(2L, 1);
        if (lower.contains("cilantro")) return new ProductLookup.Resolution(3L, 1);
        if (lower.contains("aguacate")) return new ProductLookup.Resolution(7L, 1);
        if (lower.contains("papaya")) return new ProductLookup.Resolution(8L, 1);
        if (lower.contains("cebolla")) return new ProductLookup.Resolution(4L, 1);
        if (lower.contains("limon") || lower.contains("taiti") || lower.contains("tahiti")) return new ProductLookup.Resolution(5L, 1);
        if (lower.contains("banano")) return new ProductLookup.Resolution(6L, 1);
        if (lower.contains("zanahoria")) return new ProductLookup.Resolution(9L, 1);
        if (lower.contains("habichuela")) return new ProductLookup.Resolution(10L, 1);
        if (lower.contains("pepino")) return new ProductLookup.Resolution(11L, 1);
        if (lower.contains("lulo")) return new ProductLookup.Resolution(12L, 1);
        if (lower.contains("maracuya")) return new ProductLookup.Resolution(13L, 1);
        if (lower.contains("crespa") || lower.contains("lechuga")) return new ProductLookup.Resolution(14L, 1);
        return new ProductLookup.Resolution(null, 0);
    };

    private final ExtractionInterpreter interpreter = new ExtractionInterpreter(lookup);
    private final TalonarioRasterService talonarioRaster = new TalonarioRasterService();

    private static ItemSugeridoDTO item(String mentioned, String normalized, BigDecimal qty, String unit, boolean ambiguous, String obs) {
        return new ItemSugeridoDTO(mentioned, normalized, qty, unit, ambiguous, obs);
    }

    private InterpretedOrder interpretItems(ItemSugeridoDTO... items) {
        return interpreter.interpret(new AIOrderResponse(List.of(items), ConversationAction.CONFIRM_ORDER, null, null));
    }

    // =========================================================================
    // 1. REGLA 1: LIBRAS (LB)
    // =========================================================================
    @Nested
    @DisplayName("Regla 1: Pedidos explícitos en Libras")
    class LibrasTests {

        @Test
        @DisplayName("Cliente pide '10 libras de papa' -> cantidad=10, unidad=LB")
        void testLibrasExplicit() {
            InterpretedOrder res = interpretItems(item("10 libras de papa", "Papa pastusa", new BigDecimal("10"), "LB", false, null));
            assertEquals(1, res.items().size());
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("10.000"), it.quantity());
            assertEquals(UnitOfMeasure.LB, it.unit());
            assertNull(it.manualPrice());
            assertEquals(ReviewStatus.GREEN, res.review());
        }

        @Test
        @DisplayName("Cliente pide '5 lb de tomate' con abreviatura 'lb'")
        void testLibrasAbreviation() {
            InterpretedOrder res = interpretItems(item("5 lb de tomate", "Tomate chonto", new BigDecimal("5"), "lb", false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("5.000"), it.quantity());
            assertEquals(UnitOfMeasure.LB, it.unit());
        }

        @Test
        @DisplayName("Parser tolera LIBRA, LIBRAS, LB en mayúsculas o minúsculas")
        void testUnitOfMeasureParseLibras() {
            assertEquals(UnitOfMeasure.LB, UnitOfMeasure.parse("LB").orElseThrow());
            assertEquals(UnitOfMeasure.LB, UnitOfMeasure.parse("lb").orElseThrow());
            assertEquals(UnitOfMeasure.LB, UnitOfMeasure.parse("libra").orElseThrow());
            assertEquals(UnitOfMeasure.LB, UnitOfMeasure.parse("LIBRAS").orElseThrow());
            assertEquals(UnitOfMeasure.LB, UnitOfMeasure.parse("  libras  ").orElseThrow());
        }
    }

    // =========================================================================
    // 2. REGLA 2: KILOS (KG) Y PREVENCIÓN DE ERRORES KILOS VS LIBRAS
    // =========================================================================
    @Nested
    @DisplayName("Regla 2: Pedidos en Kilos y prevención Kilos vs Libras")
    class KilosTests {

        @Test
        @DisplayName("Cliente pide '5 kilos de papa' -> cantidad=5, unidad=KG (NUNCA LB)")
        void testKilosExplicit() {
            InterpretedOrder res = interpretItems(item("5 kilos de papa", "Papa pastusa", new BigDecimal("5"), "KG", false, null));
            assertEquals(1, res.items().size());
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("5.000"), it.quantity());
            assertEquals(UnitOfMeasure.KG, it.unit());
            assertNull(it.manualPrice(), "No debe interpretarse como dinero");
        }

        @Test
        @DisplayName("Cliente pide '15 k papa' con 'k' suelta -> unidad=KG (Caso Factura #51 corregido)")
        void testKilosShorthandK() {
            InterpretedOrder res = interpretItems(item("15 k papa", "Papa pastusa", new BigDecimal("15"), "k", false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("15.000"), it.quantity());
            assertEquals(UnitOfMeasure.KG, it.unit(), "La abreviatura 'k' DEBE resolver a KG, nunca a LB ni vacío");
            assertNull(it.manualPrice(), "15k no debe confundirse con $15.000 de dinero");
        }

        @Test
        @DisplayName("Cliente pide '1.688 kg banano' con decimales (Caso Factura #122 y #15)")
        void testKilosWithDecimals() {
            InterpretedOrder res = interpretItems(item("1.688 kg banano", "Banano", new BigDecimal("1.688"), "KG", false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("1.688"), it.quantity());
            assertEquals(UnitOfMeasure.KG, it.unit());
        }

        @Test
        @DisplayName("Cliente pide '14 kg de papa' (Caso Factura #84) -> cantidad=14, unidad=KG")
        void testKilosLargeQuantity() {
            InterpretedOrder res = interpretItems(item("14 kg de papa", "Papa criolla", new BigDecimal("14"), "kg", false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("14.000"), it.quantity());
            assertEquals(UnitOfMeasure.KG, it.unit());
        }

        @Test
        @DisplayName("Parser tolera K, k, KG, kg, KILO, kilos, KL, kl.")
        void testUnitOfMeasureParseKilos() {
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("KG").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("kg").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("K").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("k").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("kilo").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("KILOS").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("kl").orElseThrow());
            assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse("KL.").orElseThrow());
        }

        @Test
        @DisplayName("'5k de papa' NUNCA debe extraerse como dinero en extractMoneyValue")
        void test5kNeverBecomesMoney() {
            ItemSugeridoDTO dto = item("5k papa", "Papa pastusa", new BigDecimal("5"), "k", false, null);
            InterpretedOrder res = interpreter.interpret(new AIOrderResponse(List.of(dto), ConversationAction.CONFIRM_ORDER, null, null));
            assertNull(res.items().get(0).manualPrice(), "5k es KILOS, nunca $5.000");
            assertEquals(UnitOfMeasure.KG, res.items().get(0).unit());
        }
    }

    // =========================================================================
    // 3. REGLA 3: UNIDADES EXPLÍCITAS
    // =========================================================================
    @Nested
    @DisplayName("Regla 3: Pedidos explícitos en Unidades")
    class UnidadesTests {

        @Test
        @DisplayName("Cliente pide '4 unidades de papa' -> cantidad=4, unidad=UNIDAD")
        void testUnidadesExplicit() {
            InterpretedOrder res = interpretItems(item("4 unidades de papa", "Papa pastusa", new BigDecimal("4"), "UNIDAD", false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("4.000"), it.quantity());
            assertEquals(UnitOfMeasure.UNIDAD, it.unit());
        }

        @Test
        @DisplayName("Cliente pide con abreviatura 'und' o 'un'")
        void testUnidadesAbreviations() {
            assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("und").orElseThrow());
            assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("UND").orElseThrow());
            assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("un").orElseThrow());
            assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("UN").orElseThrow());
            assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("unidad").orElseThrow());
            assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("unidades").orElseThrow());
        }
    }

    // =========================================================================
    // 4. REGLA 4: UNIDADES MAYORISTAS Y TRADICIONALES
    // =========================================================================
    @Nested
    @DisplayName("Regla 4: Unidades mayoristas (Caja, Bulto, Arroba, Atado, Bolsa, Paquete, Manojo)")
    class MayoristasTests {

        @Test
        @DisplayName("Bulto y fracción de bulto ('1/2 bulto')")
        void testBulto() {
            InterpretedOrder res = interpretItems(item("1/2 bulto de papa", "Papa pastusa", new BigDecimal("0.5"), "BULTO", false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("0.500"), it.quantity());
            assertEquals(UnitOfMeasure.BULTO, it.unit());
            assertEquals(UnitOfMeasure.BULTO, UnitOfMeasure.parse("bl").orElseThrow());
            assertEquals(UnitOfMeasure.BULTO, UnitOfMeasure.parse("bultos").orElseThrow());
        }

        @Test
        @DisplayName("Caja y abreviatura 'cj'")
        void testCaja() {
            InterpretedOrder res = interpretItems(item("2 cajas de tomate", "Tomate chonto", new BigDecimal("2"), "CAJA", false, null));
            assertEquals(UnitOfMeasure.CAJA, res.items().get(0).unit());
            assertEquals(UnitOfMeasure.CAJA, UnitOfMeasure.parse("cj").orElseThrow());
            assertEquals(UnitOfMeasure.CAJA, UnitOfMeasure.parse("cajas").orElseThrow());
        }

        @Test
        @DisplayName("Arroba y símbolo '@'")
        void testArroba() {
            InterpretedOrder res = interpretItems(item("1 arroba de cebolla", "Cebolla", new BigDecimal("1"), "ARROBA", false, null));
            assertEquals(UnitOfMeasure.ARROBA, res.items().get(0).unit());
            assertEquals(UnitOfMeasure.ARROBA, UnitOfMeasure.parse("@").orElseThrow());
            assertEquals(UnitOfMeasure.ARROBA, UnitOfMeasure.parse("arroba").orElseThrow());
        }

        @Test
        @DisplayName("Atado, Bolsa, Paquete, Manojo")
        void testTraditionalUnits() {
            assertEquals(UnitOfMeasure.ATADO, UnitOfMeasure.parse("atado").orElseThrow());
            assertEquals(UnitOfMeasure.BOLSA, UnitOfMeasure.parse("bolsa").orElseThrow());
            assertEquals(UnitOfMeasure.PAQUETE, UnitOfMeasure.parse("pq").orElseThrow());
            assertEquals(UnitOfMeasure.MANOJO, UnitOfMeasure.parse("manojo").orElseThrow());
        }
    }

    // =========================================================================
    // 5. REGLA 5: PEDIDOS POR VALOR / PRECIO REAL
    // =========================================================================
    @Nested
    @DisplayName("Regla 5: Pedidos por valor en dinero (Precio Real)")
    class PedidosPorValorTests {

        @Test
        @DisplayName("Cliente pide '10.000 de papa' -> manualPrice=10000, quantity=null para pesar en bodega")
        void test10MilDePapa() {
            InterpretedOrder res = interpretItems(item("10.000 de papa", "Papa pastusa", new BigDecimal("10000"), "VALOR", false, "Pedido por valor: $10000"));
            InterpretedOrder.Item it = res.items().get(0);
            assertNull(it.quantity(), "La cantidad debe quedar en blanco para que bodega la pese a mano");
            assertEquals(new BigDecimal("10000"), it.manualPrice(), "El precio manual debe ser exactamente $10.000");
            assertEquals(ReviewStatus.GREEN, res.review());
        }

        @Test
        @DisplayName("Cliente pide '2000 de cilantro' -> manualPrice=2000")
        void test2000Cilantro() {
            InterpretedOrder res = interpretItems(item("2000 de cilantro", "Cilantro", new BigDecimal("2000"), "VALOR", false, "Pedido por valor: $2000"));
            InterpretedOrder.Item it = res.items().get(0);
            assertNull(it.quantity());
            assertEquals(new BigDecimal("2000"), it.manualPrice());
        }

        @Test
        @DisplayName("Cliente pide 'mil de cilantro' / 'mil de perejil' -> manualPrice=1000")
        void testMilCilantro() {
            InterpretedOrder res = interpretItems(item("mil de cilantro", "Cilantro", new BigDecimal("1000"), "VALOR", false, "Pedido por valor: $1000"));
            InterpretedOrder.Item it = res.items().get(0);
            assertNull(it.quantity());
            assertEquals(new BigDecimal("1000"), it.manualPrice());
        }

        @Test
        @DisplayName("Cliente pide '4000 de crespa' -> manualPrice=4000")
        void test4000Crespa() {
            InterpretedOrder res = interpretItems(item("4000 de crespa", "Lechuga crespa", new BigDecimal("4000"), "VALOR", false, "Pedido por valor: $4000"));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("4000"), it.manualPrice());
        }
    }

    // =========================================================================
    // 6. REGLA 6: CANTIDAD SIN UNIDAD (NÚMERO A SECAS)
    // =========================================================================
    @Nested
    @DisplayName("Regla 6: Cantidad numérica sin unidad (número a secas)")
    class CantidadSinUnidadTests {

        @Test
        @DisplayName("Cliente pide '6 papayas' -> cantidad=6, unidad=null (NUNCA asume LB)")
        void testNumeroASecasPapayas() {
            InterpretedOrder res = interpretItems(item("6 papayas", "Papaya", new BigDecimal("6"), null, false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("6.000"), it.quantity());
            assertNull(it.unit(), "Cuando no se dice unidad, unit debe ser null, nunca asumir LB");
        }

        @Test
        @DisplayName("Cliente pide '20 de amarilla' -> cantidad=20, unidad=null")
        void testNumeroASecasAmarilla() {
            InterpretedOrder res = interpretItems(item("20 de amarilla", "Papa criolla", new BigDecimal("20"), null, false, null));
            InterpretedOrder.Item it = res.items().get(0);
            assertEquals(new BigDecimal("20.000"), it.quantity());
            assertNull(it.unit());
        }
    }

    // =========================================================================
    // 7. VERIFICACIÓN DE TALONARIO / FACTURA (SIN COLUMNA '#' Y PRECIO REAL)
    // =========================================================================
    @Nested
    @DisplayName("Formato de Talonario: Sin columna '#', precios reales y unidades correctas")
    class TalonarioFormattingTests {

        @Test
        @DisplayName("El HTML de factura NO contiene la columna '#' ni índice numérico en la izquierda")
        void testNoIndexColumnInInvoiceHtml() {
            TicketData ticket = new TicketData(
                    "OFICINA", 889, OffsetDateTime.now(), "Al Lado Poyo Rico", "3128947392", "WHOLESALE",
                    List.of(
                            new TicketData.Line("Lechuga Batavia", new BigDecimal("0.5"), "LB", null, null),
                            new TicketData.Line("Papa Pastusa", new BigDecimal("5"), "KG", null, null)
                    ),
                    null, "Calle 10 # 20-30", null, false
            );

            String html = talonarioRaster.buildA4CenteredHtml(ticket);

            // 1. No debe existir encabezado '#'
            assertFalse(html.contains("<th style=\"padding:3px;border:1.5px solid #000;width:6%;text-align:center\">#</th>"),
                    "El HTML no debe tener la cabecera '#' en la tabla");
            assertFalse(html.contains(">#<"), "No debe tener columna '#' en la tabla");

            // 2. Debe tener las 4 columnas oficiales
            assertTrue(html.contains("CANT."));
            assertTrue(html.contains("DESCRIPCIÓN"));
            assertTrue(html.contains("VR. UNIT"));
            assertTrue(html.contains("VR. TOTAL"));

            // 3. Debe respetar las unidades exactas solicitadas (KG y LB)
            assertTrue(html.contains("0.5 LB"), "Debe mostrar '0.5 LB'");
            assertTrue(html.contains("5 KG"), "Debe mostrar '5 KG'");
        }

        @Test
        @DisplayName("Línea de pedido por valor ($10.000) muestra precio real en VR. TOTAL y cantidad en blanco")
        void testMoneyLineDisplaysRealPriceInTalonario() {
            TicketData ticket = new TicketData(
                    "OFICINA", 900, OffsetDateTime.now(), "Restaurante", "3128947392", "WHOLESALE",
                    List.of(
                            new TicketData.Line("Cilantro", null, "VALOR", new BigDecimal("2000"), new BigDecimal("2000")),
                            new TicketData.Line("Papa Pastusa", null, "VALOR", new BigDecimal("10000"), new BigDecimal("10000"))
                    ),
                    new BigDecimal("12000"), "Calle 10", null, false
            );

            String html = talonarioRaster.buildA4CenteredHtml(ticket);

            // Debe mostrar el precio real formateado (con punto o coma según locale)
            assertTrue(html.contains("2.000") || html.contains("2,000"), "Debe contener el valor real $ 2.000");
            assertTrue(html.contains("10.000") || html.contains("10,000"), "Debe contener el valor real $ 10.000");
            assertTrue(html.contains("12.000") || html.contains("12,000"), "Debe contener el total real $ 12.000");
        }
    }

    // =========================================================================
    // 8. PREVENCIÓN DE ERROR DE DESBORDAMIENTO EN BD (CASO ORDEN #493)
    // =========================================================================
    @Nested
    @DisplayName("Seguridad BD: Longitud de nombre de producto nunca supera 120 caracteres")
    class VarcharSafetyTests {

        @Test
        @DisplayName("buildProductNameWithNote recorta observaciones muy largas para nunca exceder 120 caracteres")
        void testProductNameNeverExceeds120Chars() {
            String base = "Papa Cortada";
            String longObs = "Unidad 'ml' no estándar para papa, posible error de escritura por 'lb'. Producto no está en catálogo candidato de la tienda.";
            String result = SaleService.buildProductNameWithNote(base, longObs);

            assertNotNull(result);
            assertTrue(result.length() <= 120, "El nombre de producto con nota no debe exceder los 120 caracteres de sale_item.product_name");
            assertTrue(result.startsWith("Papa Cortada ("));
        }
    }
}
