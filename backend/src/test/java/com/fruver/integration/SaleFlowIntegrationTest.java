package com.fruver.integration;

import com.fruver.application.dto.AIOrderResponse;
import com.fruver.application.dto.ItemSugeridoDTO;
import com.fruver.application.port.ExtractionUnavailableException;
import com.fruver.application.port.OrderExtractor;
import com.fruver.application.port.ThermalPrinterService;
import com.fruver.application.service.*;
import com.fruver.domain.enums.ConversationAction;
import com.fruver.domain.enums.PriceType;
import com.fruver.infrastructure.persistence.OrderRepository;
import com.fruver.infrastructure.persistence.Rows.ItemRow;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Flujo completo contra PostgreSQL real (Testcontainers). Se omite si no hay Docker. */
@SpringBootTest(properties = {
        "app.security.admin-password=test-only-password",
        "app.audio.storage-path=target/test-audio",
        "app.printing.poll-ms=3600000",
        "app.conversation.ai-auto-reply=false",
        "app.conversation.auto-confirm-clear-orders=false"
})
@Testcontainers(disabledWithoutDocker = true)
class SaleFlowIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @MockitoBean OrderExtractor extractor;
    @MockitoBean ThermalPrinterService printer;

    @Autowired MessageIngestService ingest;
    @Autowired SaleService sales;
    @Autowired OrderEditService edits;
    @Autowired PriceService prices;
    @Autowired PrintService print;
    @Autowired ConversationService conversation;
    @Autowired OrderRepository orders;
    @Autowired OrderViewService views;
    @Autowired JdbcClient jdbc;

    private static AIOrderResponse papaCriolla(String qty, String unit) {
        return new AIOrderResponse(List.of(new ItemSugeridoDTO("papa criolla", "Papa criolla", new BigDecimal(qty), unit, false, null)),
                ConversationAction.CONFIRM_ORDER, null, null);
    }

    private long newOrder(String phone, AIOrderResponse ai) {
        when(extractor.extract(any())).thenReturn(ai);
        ingest.ingestText(phone, "Cliente Test", "ext-" + UUID.randomUUID(), "mensaje");
        return awaitReview(phone);
    }

    private long awaitReview(String phone) {
        await().atMost(10, TimeUnit.SECONDS).until(() -> jdbc.sql("SELECT count(*) FROM orders WHERE customer_phone = :p AND status = 'REVIEW'")
                .param("p", phone).query(Integer.class).single() > 0);
        return jdbc.sql("SELECT id FROM orders WHERE customer_phone = :p ORDER BY id DESC LIMIT 1").param("p", phone).query(Long.class).single();
    }

    private long papaId() { return jdbc.sql("SELECT id FROM product WHERE name = 'Papa criolla'").query(Long.class).single(); }

    private BigDecimal price(PriceType t) {
        return jdbc.sql("SELECT amount FROM product_price WHERE product_id = :p AND price_type = :t")
                .param("p", papaId()).param("t", t.name()).query(BigDecimal.class).single();
    }

    @Test void wholesaleCustomerGetsWholesalePriceAndSnapshotSurvivesPriceChange() {
        String phone = "570000000001";
        long order = newOrder(phone, papaCriolla("3", "LB"));
        jdbc.sql("UPDATE customer SET customer_type = 'WHOLESALE' WHERE phone = :p").param("p", phone).update();
        BigDecimal wholesale = price(PriceType.WHOLESALE);

        var result = sales.approve(order, orders.find(order).orElseThrow().version(), "tester");
        assertEquals(wholesale.multiply(new BigDecimal("3")), result.total());

        prices.setPrice(papaId(), PriceType.WHOLESALE, wholesale.add(new BigDecimal("1000")), "admin");
        BigDecimal applied = jdbc.sql("SELECT applied_price FROM sale_item WHERE sale_id = :s").param("s", result.saleId()).query(BigDecimal.class).single();
        assertEquals(0, wholesale.compareTo(applied), "la venta historica no debe cambiar");
        assertEquals(1, jdbc.sql("SELECT count(*) FROM product_price_history WHERE product_id = :p AND changed_by = 'admin'")
                .param("p", papaId()).query(Integer.class).single());
    }

    @Test void approvingTwiceCreatesOnlyOneSale() {
        long order = newOrder("570000000002", papaCriolla("2", "LB"));
        int v = orders.find(order).orElseThrow().version();
        var first = sales.approve(order, v, "tester");
        var second = sales.approve(order, v, "otro");
        assertEquals(first.saleId(), second.saleId());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sale WHERE order_id = :o").param("o", order).query(Integer.class).single());
    }

    @Test void staleVersionIsRejected() {
        long order = newOrder("570000000003", papaCriolla("2", "LB"));
        int stale = orders.find(order).orElseThrow().version();
        ItemRow item = orders.items(order).get(0);
        edits.update(order, new OrderEditService.OrderEdit(stale, null, null, null,
                List.of(new OrderEditService.ItemEdit(item.id(), papaId(), new BigDecimal("4"), "LB", null, null))), "empleado1");
        ApiException e = assertThrows(ApiException.class, () -> sales.approve(order, stale, "empleado2"));
        assertEquals("ORDER_CONFLICT", e.code());
    }

    @Test void manualPriceIsSnapshottedWithCatalogPrice() {
        long order = newOrder("570000000004", papaCriolla("2", "LB"));
        var row = orders.find(order).orElseThrow();
        ItemRow item = orders.items(order).get(0);
        edits.update(order, new OrderEditService.OrderEdit(row.version(), null, null, null,
                List.of(new OrderEditService.ItemEdit(item.id(), papaId(), new BigDecimal("2"), "LB", new BigDecimal("4000"), "cliente frecuente"))), "empleado1");
        var result = sales.approve(order, orders.find(order).orElseThrow().version(), "empleado1");
        var r = jdbc.sql("SELECT catalog_price, applied_price, price_manually_changed, subtotal FROM sale_item WHERE sale_id = :s").param("s", result.saleId())
                .query((rs, n) -> new Object[]{rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBoolean(3), rs.getBigDecimal(4)}).single();
        assertEquals(0, price(PriceType.RETAIL).compareTo((BigDecimal) r[0]));
        assertEquals(0, new BigDecimal("4000").compareTo((BigDecimal) r[1]));
        assertEquals(true, r[2]);
        assertEquals(0, new BigDecimal("8000").compareTo((BigDecimal) r[3]));
    }

    @Test void unitDifferentFromProductUnitBlocksApproval() {
        long order = newOrder("570000000005", papaCriolla("2", "KG")); // el producto se vende por LB
        ApiException e = assertThrows(ApiException.class, () -> sales.approve(order, orders.find(order).orElseThrow().version(), "tester"));
        assertEquals("ORDER_REQUIRES_REVIEW", e.code());
        assertTrue(e.details().stream().anyMatch(d -> d.contains("UNIT_MISMATCH")));
    }

    @Test void printerFailureKeepsSaleAndReprintDoesNotCreateAnotherSale() {
        while (print.processOne()) { /* drena trabajos de otras pruebas */ }
        long order = newOrder("570000000006", papaCriolla("1", "LB"));
        var result = sales.approve(order, orders.find(order).orElseThrow().version(), "tester");
        doThrow(new ThermalPrinterService.PrinterException("apagada", null)).when(printer).print(any());
        for (int i = 0; i < 3; i++) {
            jdbc.sql("UPDATE print_job SET next_attempt_at = now() WHERE sale_id = :s AND status = 'PENDING'").param("s", result.saleId()).update();
            print.processOne();
        }
        assertEquals("PRINT_FAILED", jdbc.sql("SELECT print_status FROM sale WHERE id = :s").param("s", result.saleId()).query(String.class).single());

        doNothing().when(printer).print(any());
        sales.reprint(result.saleId(), "tester");
        while (print.processOne()) { /* drena */ }
        assertEquals("PRINTED", jdbc.sql("SELECT print_status FROM sale WHERE id = :s").param("s", result.saleId()).query(String.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM sale WHERE order_id = :o").param("o", order).query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'REPRINT_REQUESTED' AND sale_id = :s").param("s", result.saleId()).query(Integer.class).single());
    }

    @Test void aiDownKeepsTheOrderInRedAndItCanBeTypedManually() {
        when(extractor.extract(any())).thenThrow(new ExtractionUnavailableException("caida", null));
        String phone = "570000000007";
        ingest.ingestText(phone, "Cliente", "ext-" + UUID.randomUUID(), "dos libras de papa criolla");
        long order = awaitReview(phone);
        var o = orders.find(order).orElseThrow();
        assertEquals("FAILED", o.aiStatus());
        assertEquals("RED", o.reviewStatus());

        edits.update(order, new OrderEditService.OrderEdit(o.version(), null, "Calle 1", null,
                List.of(new OrderEditService.ItemEdit(null, papaId(), new BigDecimal("2"), "LB", null, null))), "empleado1");
        var result = sales.approve(order, orders.find(order).orElseThrow().version(), "empleado1");
        assertTrue(result.total().signum() > 0);
    }

    @Test void duplicateExternalMessageCreatesOneOrder() {
        when(extractor.extract(any())).thenReturn(papaCriolla("1", "LB"));
        String phone = "570000000008", ext = "dup-" + UUID.randomUUID();
        ingest.ingestText(phone, "C", ext, "hola");
        ingest.ingestText(phone, "C", ext, "hola");
        awaitReview(phone);
        assertEquals(1, jdbc.sql("SELECT count(*) FROM orders WHERE customer_phone = :p").param("p", phone).query(Integer.class).single());
    }

    @Test void customerReplyAttachesToTheWaitingOrderInsteadOfCreatingANewOne() {
        String phone = "570000000009";
        when(extractor.extract(any())).thenReturn(new AIOrderResponse(
                List.of(new ItemSugeridoDTO("papa", null, new BigDecimal("5"), "LB", true, null)), ConversationAction.ASK_CUSTOMER,
                "¿Criolla, pastusa o sabanera?", "varias"));
        ingest.ingestText(phone, "C", "ext-" + UUID.randomUUID(), "mandeme 5 de papa");
        long order = awaitReview(phone);
        assertEquals("YELLOW", orders.find(order).orElseThrow().reviewStatus());

        conversation.send(order, "¿Criolla, pastusa o sabanera?", ConversationService.Kind.ASK, "empleado1");
        assertEquals("WAITING_FOR_CUSTOMER", orders.find(order).orElseThrow().conversationStatus());

        when(extractor.extract(any())).thenReturn(papaCriolla("5", "LB"));
        ingest.ingestText(phone, "C", "ext-" + UUID.randomUUID(), "criolla");
        await().atMost(10, TimeUnit.SECONDS).until(() -> "GREEN".equals(orders.find(order).orElseThrow().reviewStatus()));

        assertEquals(1, jdbc.sql("SELECT count(*) FROM orders WHERE customer_phone = :p").param("p", phone).query(Integer.class).single());
        assertEquals(3, conversation.messages(order).size());
        assertNull(views.get(order).saleId(), "una confirmacion o respuesta NO es una venta");
    }

    @Test void sameProductOnTwoLinesIsRejectedByTheServer() {
        long order = newOrder("570000000010", papaCriolla("2", "LB"));
        var row = orders.find(order).orElseThrow();
        ItemRow item = orders.items(order).get(0);
        ApiException e = assertThrows(ApiException.class, () -> edits.update(order, new OrderEditService.OrderEdit(row.version(), null, null, null,
                List.of(new OrderEditService.ItemEdit(item.id(), papaId(), new BigDecimal("2"), "LB", null, null),
                        new OrderEditService.ItemEdit(null, papaId(), new BigDecimal("3"), "LB", null, null))), "empleado1"));
        assertEquals("ITEM_DUPLICATED", e.code());
        assertEquals(row.version(), orders.find(order).orElseThrow().version(), "no debe guardar nada");
    }

    @Test void negativeZeroOrAbsurdValuesAreRejectedByServerAndDatabase() {
        long order = newOrder("570000000011", papaCriolla("2", "LB"));
        var row = orders.find(order).orElseThrow();
        ItemRow item = orders.items(order).get(0);
        for (String bad : new String[]{"-9", "0", "-0.001", "100001"}) {
            ApiException e = assertThrows(ApiException.class, () -> edits.update(order, new OrderEditService.OrderEdit(row.version(), null, null, null,
                    List.of(new OrderEditService.ItemEdit(item.id(), papaId(), new BigDecimal(bad), "LB", null, null))), "empleado1"), "cantidad " + bad);
            assertEquals("ITEM_INVALID", e.code());
        }
        ApiException price = assertThrows(ApiException.class, () -> edits.update(order, new OrderEditService.OrderEdit(row.version(), null, null, null,
                List.of(new OrderEditService.ItemEdit(item.id(), papaId(), new BigDecimal("2"), "LB", new BigDecimal("-500"), null))), "empleado1"));
        assertEquals("ITEM_INVALID", price.code());
        assertEquals(row.version(), orders.find(order).orElseThrow().version(), "no debe guardar nada");
        assertEquals(0, new BigDecimal("2").compareTo(orders.items(order).get(0).quantity()));

        // Aunque alguien se salte la capa de aplicacion, la base lo rechaza (V7).
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE order_item SET quantity = -9 WHERE id = :id").param("id", item.id()).update());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.sql("UPDATE order_item SET manual_price = -1 WHERE id = :id").param("id", item.id()).update());
    }
}
