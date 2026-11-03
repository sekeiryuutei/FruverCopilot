package com.fruver.interfaces.rest;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.service.DeliveryPersonService;
import com.fruver.application.service.MessageIngestService;
import com.fruver.application.service.PriceService;
import com.fruver.application.service.ProductCatalogService;
import com.fruver.application.service.SseHub;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.PriceType;

class ControllersUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void productControllerListAndCreate() {
        AtomicLong updatedId = new AtomicLong();
        ProductCatalogService catalog = new ProductCatalogService(null, null) {
            @Override
            public List<ProductView> list(String search, boolean all) {
                return List.of(new ProductView(1L, "Papa Pastusa", "KG", true, null, Map.of()));
            }
            @Override
            public long create(String name, String unit, String imageUrl) {
                return 10L;
            }
            @Override
            public void update(long id, String name, String unit, Boolean active, String imageUrl) {
                updatedId.set(id);
            }
        };

        List<String> setPrices = new ArrayList<>();
        PriceService prices = new PriceService(null, null) {
            @Override
            public void setPrice(long productId, PriceType type, BigDecimal amount, String changedBy) {
                setPrices.add(productId + ":" + type + ":" + amount);
            }
        };

        ProductController pc = new ProductController(catalog, prices);

        var list = pc.list("papa", false);
        assertEquals(1, list.size());
        assertEquals("Papa Pastusa", list.get(0).name());

        var res = pc.create(new ProductController.CreateBody("Tomate", "KG", new BigDecimal("3500"), new BigDecimal("3000"), null));
        assertEquals(10L, res.get("id"));
        assertTrue(setPrices.contains("10:RETAIL:3500"));
        assertTrue(setPrices.contains("10:WHOLESALE:3000"));

        pc.patch(10L, new ProductController.PatchBody("Tomate Chonto", "KG", true, null));
        assertEquals(10L, updatedId.get());
    }

    @Test
    void deliveryPersonControllerCrud() {
        AtomicBoolean updated = new AtomicBoolean(false);
        AtomicBoolean deleted = new AtomicBoolean(false);
        DeliveryPersonService deliveryPersonService = new DeliveryPersonService(null, null) {
            @Override
            public List<DeliveryPersonView> list(boolean includeInactive) {
                return List.of(new DeliveryPersonView(1L, "Carlos", "3001234567", true, OffsetDateTime.now()));
            }
            @Override
            public DeliveryPersonView create(String name, String phone, String actor) {
                return new DeliveryPersonView(1L, name, phone, true, OffsetDateTime.now());
            }
            @Override
            public DeliveryPersonView update(long id, String name, String phone, Boolean active, String actor) {
                updated.set(true);
                return new DeliveryPersonView(id, name, phone, active, OffsetDateTime.now());
            }
            @Override
            public void delete(long id, String actor) {
                deleted.set(true);
            }
        };

        DeliveryPersonController dc = new DeliveryPersonController(deliveryPersonService);

        assertEquals(1, dc.list(false).size());

        var created = dc.create(new DeliveryPersonController.DeliveryPersonBody("Carlos", "3001234567", true));
        assertEquals("Carlos", created.name());

        dc.update(1L, new DeliveryPersonController.DeliveryPersonBody("Carlos M", "3001234567", true));
        assertTrue(updated.get());

        dc.delete(1L);
        assertTrue(deleted.get());
    }

    @Test
    void whatsAppWebhookControllerVerifyAndReceive() {
        AppProperties.Whatsapp wa = new AppProperties.Whatsapp(true, "verify-token-123", "ignore", "token", "phone-id", "v21.0");
        AppProperties props = new AppProperties("America/Bogota", false, null, null, null, wa, null, null, null, null);

        AtomicBoolean ingested = new AtomicBoolean(false);
        MessageIngestService ingest = new MessageIngestService(null, null, null, null, null) {
            @Override
            public void ingestText(String from, String senderName, String messageId, String text) {
                ingested.set(true);
            }
        };

        WhatsAppWebhookController controller = new WhatsAppWebhookController(props, ingest, mapper);

        ResponseEntity<String> verifySuccess = controller.verify("subscribe", "verify-token-123", "chal-abc");
        assertEquals(200, verifySuccess.getStatusCode().value());
        assertEquals("chal-abc", verifySuccess.getBody());

        ResponseEntity<String> verifyFail = controller.verify("subscribe", "wrong-token", "chal-abc");
        assertEquals(403, verifyFail.getStatusCode().value());

        String jsonText = """
                {
                  "entry": [{
                    "changes": [{
                      "value": {
                        "contacts": [{"wa_id": "573151234567", "profile": {"name": "Don Pedro"}}],
                        "messages": [{"id": "m1", "from": "573151234567", "type": "text", "text": {"body": "2 kilos de papa"}}]
                      }
                    }]
                  }]
                }
                """;
        MockHttpServletRequest req = new MockHttpServletRequest();
        var res = controller.receive(jsonText, null, req);
        assertEquals(200, res.getStatusCode().value());
        assertTrue(ingested.get());
    }

    @Test
    void streamControllerReturnsEmitterAndMe() {
        SseHub sseHub = new SseHub() {
            @Override
            public SseEmitter subscribe() {
                return new SseEmitter();
            }
        };
        StreamController sc = new StreamController(sseHub);
        assertNotNull(sc.stream());

        var auth = SecurityContextHolder.getContext().getAuthentication();
        var me = sc.me(auth);
        assertEquals("admin", me.get("username"));
        assertEquals("ADMIN", me.get("role"));
    }
}
