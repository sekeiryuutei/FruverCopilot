package com.fruver.interfaces.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.service.*;
import com.fruver.config.AppProperties;
import com.fruver.domain.enums.PriceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ControllersUnitTest {

    @Mock ProductCatalogService catalog;
    @Mock PriceService prices;
    @Mock DeliveryPersonService deliveryPersonService;
    @Mock MessageIngestService ingest;
    @Mock SseHub sseHub;

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
        ProductController pc = new ProductController(catalog, prices);

        when(catalog.list("papa", false)).thenReturn(List.of(
                new ProductCatalogService.ProductView(1L, "Papa Pastusa", "KG", true, null, Map.of())
        ));
        var list = pc.list("papa", false);
        assertEquals(1, list.size());
        assertEquals("Papa Pastusa", list.get(0).name());

        when(catalog.create("Tomate", "KG", null)).thenReturn(10L);
        var res = pc.create(new ProductController.CreateBody("Tomate", "KG", new BigDecimal("3500"), new BigDecimal("3000"), null));
        assertEquals(10L, res.get("id"));
        verify(prices).setPrice(10L, PriceType.RETAIL, new BigDecimal("3500"), "admin");
        verify(prices).setPrice(10L, PriceType.WHOLESALE, new BigDecimal("3000"), "admin");

        pc.patch(10L, new ProductController.PatchBody("Tomate Chonto", "KG", true, null));
        verify(catalog).update(10L, "Tomate Chonto", "KG", true, null);
    }

    @Test
    void deliveryPersonControllerCrud() {
        DeliveryPersonController dc = new DeliveryPersonController(deliveryPersonService);

        when(deliveryPersonService.list(false)).thenReturn(List.of(
                new DeliveryPersonService.DeliveryPersonView(1L, "Carlos", "3001234567", true, OffsetDateTime.now())
        ));
        assertEquals(1, dc.list(false).size());

        when(deliveryPersonService.create("Carlos", "3001234567", "admin")).thenReturn(
                new DeliveryPersonService.DeliveryPersonView(1L, "Carlos", "3001234567", true, OffsetDateTime.now())
        );
        var created = dc.create(new DeliveryPersonController.DeliveryPersonBody("Carlos", "3001234567", true));
        assertEquals("Carlos", created.name());

        dc.update(1L, new DeliveryPersonController.DeliveryPersonBody("Carlos M", "3001234567", true));
        verify(deliveryPersonService).update(1L, "Carlos M", "3001234567", true, "admin");

        dc.delete(1L);
        verify(deliveryPersonService).delete(1L, "admin");
    }

    @Test
    void whatsAppWebhookControllerVerifyAndReceive() {
        AppProperties props = mock(AppProperties.class);
        when(props.whatsapp()).thenReturn(new AppProperties.Whatsapp(true, "verify-token-123", "ignore", "token", "phone-id", "v21.0"));

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
        var res = controller.receive(jsonText, null, mock(jakarta.servlet.http.HttpServletRequest.class));
        assertEquals(200, res.getStatusCode().value());
    }

    @Test
    void streamControllerReturnsEmitterAndMe() {
        StreamController sc = new StreamController(sseHub);
        when(sseHub.subscribe()).thenReturn(mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class));
        assertNotNull(sc.stream());
        verify(sseHub).subscribe();

        var auth = SecurityContextHolder.getContext().getAuthentication();
        var me = sc.me(auth);
        assertEquals("admin", me.get("username"));
        assertEquals("ADMIN", me.get("role"));
    }
}
