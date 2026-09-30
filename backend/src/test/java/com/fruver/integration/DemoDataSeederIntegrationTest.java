package com.fruver.integration;

import com.fruver.application.dto.OrderView;
import com.fruver.application.service.OrderViewService;
import com.fruver.config.DemoDataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.security.admin-password=test-only-password", "app.audio.storage-path=target/test-audio",
        "app.printing.poll-ms=3600000", "app.demo-seed.enabled=true"})
@Testcontainers(disabledWithoutDocker = true)
class DemoDataSeederIntegrationTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DemoDataSeeder seeder;
    @Autowired OrderViewService views;
    @Autowired JdbcClient jdbc;

    private BigDecimal saleTotal(long order) {
        return jdbc.sql("SELECT total FROM sale WHERE order_id = :o").param("o", order).query(BigDecimal.class).single();
    }

    private int count(String sql) { return jdbc.sql(sql).query(Integer.class).single(); }

    @Test void seedsFinishedAndPendingOrdersWithRealSnapshots() {
        assertEquals(0, new BigDecimal("14000").compareTo(saleTotal(10003)));
        assertEquals(0, new BigDecimal("13400").compareTo(saleTotal(10004)));
        assertEquals(2, count("SELECT count(*) FROM sale_item WHERE sale_id = (SELECT id FROM sale WHERE order_id = 10003)"));
        assertEquals(2, count("SELECT count(*) FROM sale WHERE print_status = 'PRINTED' AND order_id IN (10003, 10004)"));
        assertEquals(0, count("SELECT count(*) FROM print_job WHERE status = 'PENDING'"), "no debe imprimir tickets de demo");

        OrderView pending = views.get(10005);
        assertEquals("REVIEW", pending.status());
        assertEquals(2, pending.items().size());
        assertTrue(pending.complete());
        assertEquals(0, new BigDecimal("21000").compareTo(pending.total()));
        assertNull(pending.saleId());
    }

    @Test void isIdempotentAndResetRestoresThePendingOrder() {
        seeder.seed(false);
        assertEquals(3, count("SELECT count(*) FROM orders WHERE id IN (10003, 10004, 10005)"));
        assertEquals(2, count("SELECT count(*) FROM sale"));

        jdbc.sql("UPDATE orders SET status = 'CANCELLED' WHERE id = 10005").update(); // simula una demo que dejo el pedido usado
        seeder.seed(true);
        assertEquals("REVIEW", views.get(10005).status());
        assertEquals(2, count("SELECT count(*) FROM sale"));
        assertTrue(jdbc.sql("SELECT nextval('order_number_seq')").query(Long.class).single() > 10005, "la secuencia no debe chocar con los ids demo");
    }
}
