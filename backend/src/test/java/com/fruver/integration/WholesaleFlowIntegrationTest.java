package com.fruver.integration;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.OrderExtractor;
import com.fruver.application.port.ThermalPrinterService;
import com.fruver.application.service.MessageIngestService;
import com.fruver.application.service.PrintService;
import com.fruver.application.service.SaleService;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.ConversationAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "app.security.admin-password=test-only-password",
        "app.audio.storage-path=target/test-audio",
        "app.printing.poll-ms=3600000",
        "app.printing.auto-print-wholesale=true",
        "app.printing.wholesale-grace-seconds=0",
        "app.conversation.ai-auto-reply=false",
        "app.conversation.auto-confirm-clear-orders=false"
})
@Testcontainers(disabledWithoutDocker = true)
class WholesaleFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean OrderExtractor extractor;
    @MockitoBean ThermalPrinterService printer;

    @Autowired MessageIngestService ingest;
    @Autowired SaleService sales;
    @Autowired PrintService print;
    @Autowired JdbcClient jdbc;
    @Autowired AppProperties props;
    @Autowired com.fruver.interfaces.rest.SaleController saleController;

    @Test
    void imageOrderAutoApprovesAndEnqueuesPrintJob() {
        while (print.processOne()) { /* drenar */ }
        String phone = "573159990001";
        AIOrderResponse ai = new AIOrderResponse(
                List.of(
                        new ItemSugeridoDTO("3 bultos papa pastusa", "Papa Pastusa", new BigDecimal("3"), "BULTO", false, null),
                        new ItemSugeridoDTO("2 cajas tomate", "Tomate Chonto", new BigDecimal("2"), "CAJA", false, null)
                ),
                ConversationAction.CONFIRM_ORDER,
                null,
                "Lista manuscrita de libreta leída exitosamente"
        );

        when(extractor.extractImage(any(), any(), any())).thenReturn(ai);

        byte[] fakeImage = new byte[]{ (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00 };
        ingest.ingestImageBytes(phone, "Restaurante El Paisa", "img-" + UUID.randomUUID(), fakeImage, "image/jpeg", "Foto del pedido de hoy");

        // En modo mayorista con autoPrintWholesale=true, se auto-aprueba y genera sale + print_job de inmediato
        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("""
                SELECT count(*) FROM orders o
                JOIN sale s ON s.order_id = o.id
                JOIN print_job pj ON pj.sale_id = s.id
                WHERE o.customer_phone = :p AND o.status = 'APPROVED'
                """).param("p", phone).query(Integer.class).single() > 0);

        Long orderId = jdbc.sql("SELECT id FROM orders WHERE customer_phone = :p").param("p", phone).query(Long.class).single();
        Long saleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).single();

        assertNotNull(orderId);
        assertNotNull(saleId);

        // Verificar columnas de impresora en sale y print_job
        String targetPrinter = jdbc.sql("SELECT target_printer FROM print_job WHERE sale_id = :s").param("s", saleId).query(String.class).single();
        assertNotNull(targetPrinter);
        assertTrue("OFICINA".equals(targetPrinter) || "CASA".equals(targetPrinter));

        // Procesar la cola de impresión
        while (print.processOne()) { /* procesar */ }

        String printStatus = jdbc.sql("SELECT print_status FROM sale WHERE id = :s").param("s", saleId).query(String.class).single();
        assertEquals("PRINTED", printStatus);

        String printedLoc = jdbc.sql("SELECT printed_printer FROM sale WHERE id = :s").param("s", saleId).query(String.class).single();
        assertEquals(targetPrinter, printedLoc);
    }

    @Test
    void doubtfulOrderSetsVerificationFlag() {
        String phone = "573159990002";
        AIOrderResponse doubtfulAi = new AIOrderResponse(
                List.of(
                        new ItemSugeridoDTO("2 algo ilegible de cebolla", null, new BigDecimal("2"), "UNIDAD", true, "Letra borrosa")
                ),
                ConversationAction.CONFIRM_ORDER,
                null,
                "Foto algo oscura con letra borrosa en línea 1"
        );

        when(extractor.extract(any())).thenReturn(doubtfulAi);
        ingest.ingestText(phone, "Don Pedro", "ext-" + UUID.randomUUID(), "2 de cebolla");

        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("""
                SELECT count(*) FROM orders WHERE customer_phone = :p AND status = 'APPROVED'
                """).param("p", phone).query(Integer.class).single() > 0);

        Boolean reqVer = jdbc.sql("SELECT requires_verification FROM orders WHERE customer_phone = :p").param("p", phone).query(Boolean.class).single();
        assertTrue(reqVer, "Un pedido con ítems ambiguos debe marcar requires_verification=true");
    }

    @Test
    void reprintByOrderIdAndBulkReprintWork() {
        String phone = "573159990003";
        AIOrderResponse ai = new AIOrderResponse(
                List.of(new ItemSugeridoDTO("1 bulto zanahoria", "Zanahoria", new BigDecimal("1"), "BULTO", false, null)),
                ConversationAction.CONFIRM_ORDER, null, "Ok"
        );
        when(extractor.extract(any())).thenReturn(ai);
        ingest.ingestText(phone, "Doña Flor", "ext-" + UUID.randomUUID(), "1 bulto zanahoria");

        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("SELECT count(*) FROM orders WHERE customer_phone = :p AND status = 'APPROVED'")
                .param("p", phone).query(Integer.class).single() > 0);

        Long orderId = jdbc.sql("SELECT id FROM orders WHERE customer_phone = :p").param("p", phone).query(Long.class).single();
        while (print.processOne()) { /* Imprime la comanda inicial */ }

        // Simular fallo
        Long saleId = jdbc.sql("SELECT id FROM sale WHERE order_id = :o").param("o", orderId).query(Long.class).single();
        jdbc.sql("UPDATE print_job SET status = 'FAILED', last_error = 'Sin papel' WHERE sale_id = :s").param("s", saleId).update();
        jdbc.sql("UPDATE sale SET print_status = 'PRINT_FAILED', print_error = 'Sin papel' WHERE id = :s").param("s", saleId).update();

        // 1. Probar reprintAllPending (re-encola y ejecuta de inmediato la impresión pendiente)
        int enqueued = print.reprintAllPending();
        assertTrue(enqueued > 0);

        String pjStatus = jdbc.sql("SELECT status FROM print_job WHERE sale_id = :s ORDER BY id DESC LIMIT 1").param("s", saleId).query(String.class).single();
        assertEquals("DONE", pjStatus);
        assertEquals("PRINTED", jdbc.sql("SELECT print_status FROM sale WHERE id = :s").param("s", saleId).query(String.class).single());

        // 2. Probar reprintByOrderId
        Long sId = sales.reprintByOrderId(orderId, "operador");
        assertEquals(saleId, sId);
    }

    @Test
    void ticketByOrderOnUnapprovedOrderDoesNotApproveNorCreateSale() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "pass",
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        String phone = "573159990004";
        AIOrderResponse ai = new AIOrderResponse(
                List.of(new ItemSugeridoDTO("5 kilos de papa", "Papa Pastusa", new BigDecimal("5"), "KG", false, null)),
                ConversationAction.CONFIRM_ORDER, null, "Ok"
        );
        when(extractor.extract(any())).thenReturn(ai);
        ingest.ingestText(phone, "Restaurante Central", "ext-" + UUID.randomUUID(), "5 kilos de papa");

        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("""
                SELECT count(*) FROM order_item oi
                JOIN orders o ON o.id = oi.order_id
                WHERE o.customer_phone = :p
                """).param("p", phone).query(Integer.class).single() > 0);

        Long orderId = jdbc.sql("SELECT id FROM orders WHERE customer_phone = :p").param("p", phone).query(Long.class).single();
        jdbc.sql("DELETE FROM print_job WHERE sale_id IN (SELECT id FROM sale WHERE order_id = :o)").param("o", orderId).update();
        jdbc.sql("DELETE FROM sale_item WHERE sale_id IN (SELECT id FROM sale WHERE order_id = :o)").param("o", orderId).update();
        jdbc.sql("DELETE FROM sale WHERE order_id = :o").param("o", orderId).update();
        jdbc.sql("UPDATE orders SET status = 'REVIEW', conversation_status = 'WAITING_FOR_MORE' WHERE id = :id").param("id", orderId).update();

        var preview = saleController.ticketByOrder(orderId);
        assertNotNull(preview);
        assertNotNull(preview.get("text"));
        assertTrue(preview.get("text").toString().toLowerCase().contains("papa pastusa"));

        int saleCount = jdbc.sql("SELECT count(*) FROM sale WHERE order_id = :o").param("o", orderId).query(Integer.class).single();
        assertEquals(0, saleCount, "ticketByOrder NO debe crear una venta");
        String status = jdbc.sql("SELECT status FROM orders WHERE id = :id").param("id", orderId).query(String.class).single();
        assertEquals("REVIEW", status, "El pedido debe permanecer en REVIEW al previsualizar la factura");
    }

    @Test
    void subsequentAdditionsMergeItemsWithoutOverwriting() {
        String phone = "573159990005";
        AIOrderResponse aiTurn1 = new AIOrderResponse(
                List.of(
                        new ItemSugeridoDTO("10 kilos de papa", "Papa Pastusa", new BigDecimal("10"), "KG", false, null),
                        new ItemSugeridoDTO("2 libras de cebolla", "Cebolla Cabezona", new BigDecimal("2"), "LB", false, null)
                ),
                ConversationAction.CONFIRM_ORDER, null, "Primer turno"
        );
        when(extractor.extract(any())).thenReturn(aiTurn1);
        ingest.ingestText(phone, "Comedor Familiar", "turn1-" + UUID.randomUUID(), "10 de papa y 2 de cebolla");

        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("SELECT count(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE o.customer_phone = :p")
                .param("p", phone).query(Integer.class).single() >= 2);

        AIOrderResponse aiTurn2 = new AIOrderResponse(
                List.of(
                        new ItemSugeridoDTO("1 bulto zanahoria", "Zanahoria", new BigDecimal("1"), "BULTO", false, null)
                ),
                ConversationAction.CONFIRM_ORDER, null, "Segundo turno"
        );
        when(extractor.extract(any())).thenReturn(aiTurn2);
        ingest.ingestText(phone, "Comedor Familiar", "turn2-" + UUID.randomUUID(), "agrega 1 bulto de zanahoria");

        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("SELECT count(*) FROM order_item oi JOIN orders o ON o.id = oi.order_id WHERE o.customer_phone = :p")
                .param("p", phone).query(Integer.class).single() == 3);

        List<String> items = jdbc.sql("""
                SELECT COALESCE(oi.ai_product_name, oi.mentioned) FROM order_item oi
                JOIN orders o ON o.id = oi.order_id
                WHERE o.customer_phone = :p ORDER BY oi.id
                """).param("p", phone).query(String.class).list();

        assertTrue(items.contains("Papa Pastusa"));
        assertTrue(items.contains("Cebolla Cabezona"));
        assertTrue(items.contains("Zanahoria"));
    }
}

