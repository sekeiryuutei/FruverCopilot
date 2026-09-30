package com.fruver.interfaces.rest;

import com.fruver.application.dto.OrderView;
import com.fruver.application.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public record ItemBody(Long id, Long productId, BigDecimal quantity, String unit, BigDecimal manualPrice, String priceChangeReason) {}

    public record UpdateBody(@NotNull Integer version, @Size(max = 120) String customerName, @Size(max = 300) String address,
                             @Size(max = 500) String notes, List<ItemBody> items) {}

    public record VersionBody(@NotNull Integer version) {}

    public record ReplyBody(@NotBlank @Size(max = 1000) String message, @NotNull ConversationService.Kind kind) {}

    private final OrderViewService views;
    private final OrderEditService edits;
    private final SaleService sales;
    private final ConversationService conversation;
    private final SseHub hub;
    private final AppMetrics metrics;
    private final JdbcClient jdbc;
    private final com.fruver.application.port.AudioStorageService storage;

    public OrderController(OrderViewService views, OrderEditService edits, SaleService sales, ConversationService conversation,
                           SseHub hub, AppMetrics metrics, JdbcClient jdbc, com.fruver.application.port.AudioStorageService storage) {
        this.views = views;
        this.edits = edits;
        this.sales = sales;
        this.conversation = conversation;
        this.hub = hub;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @GetMapping
    public List<OrderView> board() {
        return views.board();
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable long id) {
        return views.get(id);
    }

    @PutMapping("/{id}")
    public OrderView update(@PathVariable long id, @Valid @RequestBody UpdateBody b) {
        List<OrderEditService.ItemEdit> items = b.items() == null ? List.of() : b.items().stream()
                .map(i -> new OrderEditService.ItemEdit(i.id(), i.productId(), i.quantity(), i.unit(), i.manualPrice(), i.priceChangeReason())).toList();
        edits.update(id, new OrderEditService.OrderEdit(b.version(), b.customerName(), b.address(), b.notes(), items), Actor.name());
        hub.orderChanged(id);
        return views.get(id);
    }

    /** APROBAR E IMPRIMIR: un solo request. El backend valida, recalcula, persiste la venta y encola la impresion. */
    @PostMapping("/{id}/approve")
    public SaleService.SaleResult approve(@PathVariable long id, @Valid @RequestBody VersionBody b) {
        SaleService.SaleResult r = sales.approve(id, b.version(), Actor.name());
        metrics.approved();
        hub.orderChanged(id);
        return r;
    }

    @PostMapping("/{id}/cancel")
    public OrderView cancel(@PathVariable long id, @Valid @RequestBody VersionBody b) {
        edits.cancel(id, b.version(), Actor.name());
        hub.orderChanged(id);
        return views.get(id);
    }

    /** [PREGUNTAR AL CLIENTE] / [ENVIAR CONFIRMACION]: el empleado revisa y edita el texto antes de enviarlo. */
    @PostMapping("/{id}/reply")
    public OrderView reply(@PathVariable long id, @Valid @RequestBody ReplyBody b) {
        conversation.send(id, b.message(), b.kind(), Actor.name());
        return views.get(id);
    }

    @GetMapping("/{id}/messages")
    public List<ConversationService.MessageView> messages(@PathVariable long id) {
        return conversation.messages(id);
    }

    @GetMapping("/{id}/audio")
    public ResponseEntity<ByteArrayResource> audio(@PathVariable long id) {
        var row = jdbc.sql("SELECT storage_key, mime_type FROM audio WHERE order_id = :o ORDER BY id DESC LIMIT 1")
                .param("o", id).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ApiException(404, "AUDIO_NOT_FOUND", "El pedido no tiene audio"));
        if (row[0] == null) throw new ApiException(404, "AUDIO_NOT_FOUND", "El audio aun no esta disponible");
        String mime = row[1] == null ? "audio/ogg" : row[1].split(";")[0].trim();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(mime))
                .body(new ByteArrayResource(storage.load(row[0])));
    }
}
