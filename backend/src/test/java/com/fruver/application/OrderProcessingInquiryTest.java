package com.fruver.application;

import com.fruver.application.service.OrderProcessingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessingInquiryTest {

    // Instancia ligera para probar métodos de clasificación sin dependencias externas
    private final OrderProcessingService service = new OrderProcessingService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
    );

    @Test
    void detectsPriceAndInventoryInquiries() {
        assertTrue(service.isPriceOrInventoryInquiry("Tienes arverjas?"));
        assertTrue(service.isPriceOrInventoryInquiry("tienes arvejas"));
        assertTrue(service.isPriceOrInventoryInquiry("¿Tiene tomate chonto?"));
        assertTrue(service.isPriceOrInventoryInquiry("Hay aguacate?"));
        assertTrue(service.isPriceOrInventoryInquiry("¿A cómo la papa?"));
        assertTrue(service.isPriceOrInventoryInquiry("¿Cuánto vale el plátano?"));
        assertTrue(service.isPriceOrInventoryInquiry("¿Cuánto cuesta la libra de cebolla?"));
        assertTrue(service.isPriceOrInventoryInquiry("¿Qué frutas tienen disponibles hoy?"));
        assertTrue(service.isPriceOrInventoryInquiry("¿Venden cilantro?"));
        assertTrue(service.isPriceOrInventoryInquiry("Manejan papaya?"));
    }

    @Test
    void distinguishesOrdersFromInquiries() {
        assertFalse(service.isPriceOrInventoryInquiry("Deme 2 libras de tomate"));
        assertFalse(service.isPriceOrInventoryInquiry("Mándeme 1 kilo de papa"));
        assertFalse(service.isPriceOrInventoryInquiry("Quiero 3 aguacates"));
        assertFalse(service.isPriceOrInventoryInquiry("Favor enviar 5 de tomate"));
        assertFalse(service.isPriceOrInventoryInquiry(null));
        assertFalse(service.isPriceOrInventoryInquiry(""));
    }

    @Test
    void detectsGreetings() {
        assertTrue(service.isGreetingOnly("Buenos días"));
        assertTrue(service.isGreetingOnly("Hola buenas tardes"));
        assertTrue(service.isGreetingOnly("hola"));
        assertTrue(service.isGreetingOnly("buenas"));
        assertFalse(service.isGreetingOnly("Hola, deme 2 libras de tomate"));
    }

    @Test
    void detectsProductPhotoRequests() {
        assertTrue(service.isProductPhotoRequest("¿Tienen foto de la papaya?"));
        assertTrue(service.isProductPhotoRequest("Me manda una foto del tomate?"));
        assertTrue(service.isProductPhotoRequest("¿Tiene imagenes del producto?"));
        assertTrue(service.isProductPhotoRequest("fotos del aguacate"));
        assertFalse(service.isProductPhotoRequest("Deme 2 libras de tomate"));
        assertFalse(service.isProductPhotoRequest(""));
        assertFalse(service.isProductPhotoRequest(null));
    }

    @Test
    void detectsDeliveryTimeInquiries() {
        assertTrue(service.isDeliveryTimeInquiry("¿Cuánto se demora el pedido?"));
        assertTrue(service.isDeliveryTimeInquiry("¿A qué hora llega?"));
        assertTrue(service.isDeliveryTimeInquiry("¿Ya viene el domiciliario?"));
        assertTrue(service.isDeliveryTimeInquiry("¿Cuánto falta para que llegue?"));
        assertTrue(service.isDeliveryTimeInquiry("¿Cuánto tarda la entrega?"));
        assertTrue(service.isDeliveryTimeInquiry("¿A qué hora me entregan?"));
        assertTrue(service.isDeliveryTimeInquiry("¿Se demora mucho?"));
        assertFalse(service.isDeliveryTimeInquiry("Buenas tardes, quiero 3 libras de papa"));
        assertFalse(service.isDeliveryTimeInquiry(null));
    }

    @Test
    void detectsAsksPrice() {
        assertTrue(OrderProcessingService.asksPrice("3 libras de cebolla larga, que cuestan?"));
        assertTrue(OrderProcessingService.asksPrice("¿Cuánto cuesta la libra de tomate?"));
        assertTrue(OrderProcessingService.asksPrice("¿Cuánto vale?"));
        assertTrue(OrderProcessingService.asksPrice("a como el platano"));
        assertTrue(OrderProcessingService.asksPrice("dame el total porfa"));
        assertFalse(OrderProcessingService.asksPrice("Calle 7 norte # 2-30"));
        assertFalse(OrderProcessingService.asksPrice(null));
    }

    @Test
    void extractsCustomerName() {
        assertEquals("Briyed Molina", OrderProcessingService.extractCustomerName("A nombre de Briyed Molina, pago con 20 mil", null));
        assertEquals("Carlos Gomez", OrderProcessingService.extractCustomerName("nombre: Carlos Gomez", null));
        assertEquals("Victor Burbano", OrderProcessingService.extractCustomerName("Soy Victor Burbano y vivo en calle 5", null));
        assertEquals("Pedro Perez", OrderProcessingService.extractCustomerName("Pedro Perez", "¿A nombre de quién dejamos registrado el pedido?"));
        assertNull(OrderProcessingService.extractCustomerName("domicilio a calle 7", "¿A nombre de quién dejamos registrado el pedido?"));
        assertNull(OrderProcessingService.extractCustomerName(null, null));
    }

    @Test
    void extractsPaymentMethodAndCalculatesChange() {
        var p1 = OrderProcessingService.extractPaymentMethod("pago en efectivo con 50mil", java.math.BigDecimal.valueOf(9000));
        assertNotNull(p1);
        assertTrue(p1.isCash());
        assertEquals("Pago: Efectivo (paga con $50.000 - cambio: $41.000)", p1.note());

        var p2 = OrderProcessingService.extractPaymentMethod("con 20 mil en efectivo", java.math.BigDecimal.valueOf(9000));
        assertNotNull(p2);
        assertTrue(p2.isCash());
        assertEquals("Pago: Efectivo (paga con $20.000 - cambio: $11.000)", p2.note());

        var p3 = OrderProcessingService.extractPaymentMethod("pago por transferencia", java.math.BigDecimal.valueOf(9000));
        assertNotNull(p3);
        assertTrue(p3.isTransfer());
        assertEquals("Pago: Transferencia / Llave", p3.note());

        var p4 = OrderProcessingService.extractPaymentMethod("con Nequi", java.math.BigDecimal.valueOf(9000));
        assertNotNull(p4);
        assertTrue(p4.isTransfer());
        assertEquals("Pago: Transferencia (Nequi)", p4.note());

        var p5 = OrderProcessingService.extractPaymentMethod("plata exacta", java.math.BigDecimal.valueOf(9000));
        assertNotNull(p5);
        assertTrue(p5.isCash());
        assertEquals("Pago: Efectivo (valor exacto, sin cambio)", p5.note());
    }

    @Test
    void verifiesMissingRequirementFlow() {
        // 1. Sin dirección
        var rowNoAddr = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, null, "3001234567", null, null, "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        String q1 = service.getNextMissingRequirementQuestion(rowNoAddr);
        assertNotNull(q1);
        assertTrue(q1.contains("domicilio o recoges") && q1.contains("nombre de quién") && q1.contains("billete"));

        // 2. Recoger en tienda pero sin nombre
        var rowPickupNoName = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, null, "3001234567", "Recoges en tienda / Punto físico", null, "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        String q2 = service.getNextMissingRequirementQuestion(rowPickupNoName);
        assertNotNull(q2);
        assertTrue(q2.contains("nombre de quién"));

        // 3. Recoger en tienda con nombre -> Listo para confirmar (null)
        var rowPickupWithName = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, "Briyed Molina", "3001234567", "Recoges en tienda / Punto físico", null, "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        assertNull(service.getNextMissingRequirementQuestion(rowPickupWithName));

        // 4. Domicilio pero sin nombre ni pago
        var rowDeliveryNoNameNoPay = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, null, "3001234567", "Calle 7 norte # 2-30", null, "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        String q4 = service.getNextMissingRequirementQuestion(rowDeliveryNoNameNoPay);
        assertNotNull(q4);
        assertTrue(q4.contains("nombre de quién") && q4.contains("billete"));

        // 5. Domicilio con nombre pero sin pago
        var rowDeliveryWithNameNoPay = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, "Briyed Molina", "3001234567", "Calle 7 norte # 2-30", null, "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        String q5 = service.getNextMissingRequirementQuestion(rowDeliveryWithNameNoPay);
        assertNotNull(q5);
        assertTrue(q5.contains("Cómo vas a realizar el pago"));

        // 6. Domicilio con nombre y pago en efectivo pero sin especificar billete
        var rowDeliveryCashNoBill = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, "Briyed Molina", "3001234567", "Calle 7 norte # 2-30", "Pago: Efectivo", "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        String q6 = service.getNextMissingRequirementQuestion(rowDeliveryCashNoBill);
        assertNotNull(q6);
        assertTrue(q6.contains("Con qué billete cancelas"));

        // 7. Domicilio con todo completo (Transferencia) -> Listo para confirmar (null)
        var rowDeliveryCompleteTransfer = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, "Briyed Molina", "3001234567", "Calle 7 norte # 2-30", "Pago: Transferencia (Nequi)", "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        assertNull(service.getNextMissingRequirementQuestion(rowDeliveryCompleteTransfer));

        // 8. Domicilio con todo completo (Efectivo con cambio) -> Listo para confirmar (null)
        var rowDeliveryCompleteCash = new com.fruver.infrastructure.persistence.Rows.OrderRow(
                1L, 1L, "Briyed Molina", "3001234567", "Calle 7 norte # 2-30", "Pago: Efectivo (paga con $50.000 - cambio: $41.000)", "TEXT", "REVIEW", "READY_FOR_REVIEW",
                "GREEN", "OK", null, null, false, 1, null, null, null, "RETAIL", false, null, null, null, null, null, null
        );
        assertNull(service.getNextMissingRequirementQuestion(rowDeliveryCompleteCash));
    }
}

