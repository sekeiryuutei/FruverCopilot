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
    @Autowired CustomerService customerService;
    @Autowired TicketService tickets;
    @Autowired DeliveryZoneService deliveryZoneService;

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

    @Test void consecutiveMessagesAttachToSameActiveOrderWithoutCreatingDuplicates() {
        String phone = "570000000099";
        when(extractor.extract(any())).thenReturn(new AIOrderResponse(
                List.of(), ConversationAction.NONE, null, "Saludo"));
        ingest.ingestText(phone, "Cliente", "ext-1", "Hola");
        long order = awaitReview(phone);

        when(extractor.extract(any())).thenReturn(papaCriolla("3", "LB"));
        ingest.ingestText(phone, "Cliente", "ext-2", "Necesito 3 libras de papa criolla");
        await().atMost(10, TimeUnit.SECONDS).until(() -> !orders.items(order).isEmpty());

        assertEquals(1, jdbc.sql("SELECT count(*) FROM orders WHERE customer_phone = :p").param("p", phone).query(Integer.class).single());
        assertEquals(2, jdbc.sql("SELECT count(*) FROM conversation_message WHERE order_id = :o AND direction = 'INBOUND'").param("o", order).query(Integer.class).single());
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

    @Test void manualOrderAndCustomerAddressFlow() {
        // 1. Create customer with address
        String phone = "573109999888";
        var cust = customerService.createCustomer("Don Pedro", phone, "Calle 10 # 5-20", "Casa", com.fruver.domain.enums.CustomerType.RETAIL, "operador");
        assertNotNull(cust.id());
        assertEquals("Don Pedro", cust.name());

        // 2. Add an alternate labeled address ("Negocio")
        var addrNegocio = customerService.addAddress(cust.id(), "Negocio", "Cra 15 # 4-20 Local 2", false, "operador");
        assertNotNull(addrNegocio.id());
        assertEquals("Negocio", addrNegocio.label());

        var addresses = customerService.listAddresses(cust.id());
        assertEquals(2, addresses.size());

        // 3. Create a manual POS / call order for this customer selecting the business address
        long orderId = edits.createManualOrder(new OrderEditService.ManualOrderRequest(
                cust.id(),
                cust.name(),
                phone,
                addrNegocio.address(),
                "Entregar antes de las 11am",
                List.of(new OrderEditService.ItemEdit(null, papaId(), new BigDecimal("5"), "LB", null, null))
        ), "vendedor1");

        var view = views.get(orderId);
        assertEquals("MANUAL", view.inputType());
        assertEquals("GREEN", view.reviewStatus());
        assertEquals(addrNegocio.address(), view.address());
        assertEquals("Entregar antes de las 11am", view.notes());
        assertEquals(1, view.items().size());

        // 4. Update customer name directly via order edit
        edits.update(orderId, new OrderEditService.OrderEdit(view.version(), "Don Pedro Picapiedra", view.address(), view.notes(),
                List.of(new OrderEditService.ItemEdit(view.items().get(0).id(), papaId(), new BigDecimal("5"), "LB", null, null))), "vendedor1");

        var updatedOrder = views.get(orderId);
        assertEquals("Don Pedro Picapiedra", updatedOrder.customer().name());
        var updatedCust = customerService.search("Pedro Picapiedra");
        assertFalse(updatedCust.isEmpty());
        assertEquals("Don Pedro Picapiedra", updatedCust.get(0).name());

        // 5. Approve and generate ticket: ticket contains delivery address
        var saleResult = sales.approve(orderId, updatedOrder.version(), "vendedor1");
        assertNotNull(saleResult.saleId());
        var ticketData = tickets.build(saleResult.saleId(), false);
        assertEquals("Cra 15 # 4-20 Local 2", ticketData.address());
        var lines = TicketFormatter.format(ticketData, 32);
        boolean foundAddr = lines.stream().anyMatch(l -> l.text().contains("Cra 15 # 4-20"));
        assertTrue(foundAddr, "El ticket impreso debe contener la dirección seleccionada");
    }

    @Test
    void deliveryZonesConfigurationAndPromptFormat() {
        var created = deliveryZoneService.create("Barrio Granada", true, new BigDecimal("3500"), new BigDecimal("35000"), "25-35 min", "admin");
        assertNotNull(created.id());
        assertEquals("Barrio Granada", created.name());
        assertTrue(created.covered());

        var list = deliveryZoneService.list();
        assertTrue(list.stream().anyMatch(z -> "Barrio Granada".equals(z.name())));

        var promptStr = deliveryZoneService.formatForPrompt();
        assertTrue(promptStr.contains("Barrio Granada"));
        assertTrue(promptStr.contains("3500"));

        deliveryZoneService.update(created.id(), "Barrio Granada", true, new BigDecimal("4000"), new BigDecimal("45000"), "30-40 min", "admin");
        var updated = deliveryZoneService.list().stream().filter(z -> z.id() == created.id()).findFirst().orElseThrow();
        assertEquals(new BigDecimal("4000"), updated.fee());

        deliveryZoneService.delete(created.id(), "admin");
        assertFalse(deliveryZoneService.list().stream().anyMatch(z -> z.id() == created.id()));
    }

    @Test
    void orderDispatchAndWhatsAppInvoiceFlow() {
        String phone = "570000000008";
        long orderId = newOrder(phone, papaCriolla("4", "LB"));
        int version = orders.find(orderId).orElseThrow().version();

        // 1. Approve: auto-sends invoice via WhatsApp
        var result = sales.approve(orderId, version, "cajero1");
        assertNotNull(result.saleId());

        // Verify invoice was recorded in conversation messages
        var messages = conversation.messages(orderId);
        boolean hasInvoice = messages.stream().anyMatch(m -> "OUTBOUND".equals(m.direction()) && m.content().contains("Factura de Venta"));
        assertTrue(hasInvoice, "Debe registrarse el mensaje de factura saliente para el cliente");

        // Verify initial state: not dispatched
        var orderBefore = orders.find(orderId).orElseThrow();
        assertNull(orderBefore.dispatchedAt());

        // 2. Dispatch
        sales.dispatch(result.saleId(), "domiciliario1");

        var orderAfter = orders.find(orderId).orElseThrow();
        assertNotNull(orderAfter.dispatchedAt());
        assertEquals("domiciliario1", orderAfter.dispatchedBy());

        var saleDispatchedAt = jdbc.sql("SELECT dispatched_at FROM sale WHERE id = :s").param("s", result.saleId()).query(java.time.OffsetDateTime.class).single();
        assertNotNull(saleDispatchedAt);

        // Verify dispatch WhatsApp notification message was sent
        var messagesAfter = conversation.messages(orderId);
        boolean hasDispatchMsg = messagesAfter.stream().anyMatch(m -> "OUTBOUND".equals(m.direction()) && m.content().contains("va en camino"));
        assertTrue(hasDispatchMsg, "Debe registrarse la notificación de despacho al cliente");
    }

    @Test
    void mergeOrderIntoPendingDispatchOrder() {
        String phone = "570000000009";

        // Order 1: Approved, but pending dispatch
        long order1 = newOrder(phone, papaCriolla("2", "LB"));
        int v1 = orders.find(order1).orElseThrow().version();
        var sale1 = sales.approve(order1, v1, "cajero1");
        assertNotNull(sale1.saleId());

        // Order 2: Customer writes again asking for 3 more pounds of papa criolla
        long order2 = newOrder(phone, papaCriolla("3", "LB"));
        var view2 = views.get(order2);
        assertEquals(order1, view2.pendingDispatchOrderId(), "Debe detectar que el cliente tiene el Pedido 1 pendiente de despacho");

        // Operator merges Order 2 into Order 1
        edits.mergeIntoOrder(order2, order1, "operador1");

        // Order 2 is cancelled
        var o2After = orders.find(order2).orElseThrow();
        assertEquals("CANCELLED", o2After.status());
        assertTrue(o2After.notes().contains("Anexado al Pedido #" + order1));

        // Order 1 is reset to REVIEW with 2 + 3 = 5 LB
        var o1After = orders.find(order1).orElseThrow();
        assertEquals("REVIEW", o1After.status());
        assertNull(o1After.saleId(), "La venta previa se cancela para re-aprobar el consolidado");

        var items = orders.items(order1);
        assertEquals(1, items.size());
        assertEquals(0, new BigDecimal("5.000").compareTo(items.get(0).quantity()), "Las cantidades de papa criolla deben sumarse (2 + 3 = 5)");

        // Operator re-approves consolidated Order 1
        var finalSale = sales.approve(order1, o1After.version(), "cajero1");
        assertNotNull(finalSale.saleId());
        assertEquals("APPROVED", orders.find(order1).orElseThrow().status());
    }
}
