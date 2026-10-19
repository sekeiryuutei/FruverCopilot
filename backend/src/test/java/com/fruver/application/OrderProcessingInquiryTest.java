package com.fruver.application;

import com.fruver.application.service.OrderProcessingService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderProcessingInquiryTest {

    // Instancia ligera para probar métodos de clasificación sin dependencias externas
    private final OrderProcessingService service = new OrderProcessingService(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null
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
}

