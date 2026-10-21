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

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasAnyRole('ADMIN','OPERADOR')")
public class OrderController {

    public record ItemBody(Long id, Long productId, BigDecimal quantity, String unit, BigDecimal manualPrice, String priceChangeReason) {}

    public record UpdateBody(@NotNull Integer version, @Size(max = 120) String customerName, @Size(max = 300) String address,
                             @Size(max = 500) String notes, Long deliveryPersonId, List<ItemBody> items) {}

    public record VersionBody(@NotNull Integer version, Long deliveryPersonId) {
        public VersionBody(@NotNull Integer version) {
            this(version, null);
        }
    }

    public record ReplyBody(@NotBlank @Size(max = 1000) String message, @NotNull ConversationService.Kind kind) {}

    private final OrderViewService views;
    private final OrderEditService edits;
    private final SaleService sales;
    private final ConversationService conversation;
    private final SseHub hub;
    private final AppMetrics metrics;
    private final JdbcClient jdbc;
    private final com.fruver.application.port.AudioStorageService storage;
    private final MessageIngestService ingest;

    public OrderController(OrderViewService views, OrderEditService edits, SaleService sales, ConversationService conversation,
                           SseHub hub, AppMetrics metrics, JdbcClient jdbc, com.fruver.application.port.AudioStorageService storage,
                           MessageIngestService ingest) {
        this.views = views;
        this.edits = edits;
        this.sales = sales;
        this.conversation = conversation;
        this.hub = hub;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.storage = storage;
        this.ingest = ingest;
    }

    @GetMapping
    public List<OrderView> board() {
        return views.board();
    }

    @GetMapping("/{id}")
    public OrderView get(@PathVariable long id) {
        return views.get(id);
    }

    @PostMapping("/manual")
    public OrderView createManual(@Valid @RequestBody OrderEditService.ManualOrderRequest req) {
        long id = edits.createManualOrder(req, Actor.name());
        hub.orderChanged(id);
        return views.get(id);
    }

    @PutMapping("/{id}")
    public OrderView update(@PathVariable long id, @Valid @RequestBody UpdateBody b) {
        List<OrderEditService.ItemEdit> items = b.items() == null ? List.of() : b.items().stream()
                .map(i -> new OrderEditService.ItemEdit(i.id(), i.productId(), i.quantity(), i.unit(), i.manualPrice(), i.priceChangeReason())).toList();
        edits.update(id, new OrderEditService.OrderEdit(b.version(), b.customerName(), b.address(), b.notes(), b.deliveryPersonId(), items), Actor.name());
        hub.orderChanged(id);
        return views.get(id);
    }

    /** APROBAR E IMPRIMIR: un solo request. El backend valida, recalcula, persiste la venta y encola la impresion. */
    @PostMapping("/{id}/approve")
    public SaleService.SaleResult approve(@PathVariable long id, @Valid @RequestBody VersionBody b) {
        SaleService.SaleResult r = sales.approve(id, b.version(), b.deliveryPersonId(), Actor.name());
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

    @PostMapping("/{id}/merge-into/{targetId}")
    public java.util.Map<String, Object> mergeInto(@PathVariable long id, @PathVariable long targetId) {
        edits.mergeIntoOrder(id, targetId, Actor.name());
        hub.orderChanged(id);
        hub.orderChanged(targetId);
        return java.util.Map.of("status", "MERGED", "sourceOrderId", id, "targetOrderId", targetId);
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
        // Whitelist: el mime lo controla quien sube el audio (simulador/Meta). Sin esto, un text/html
        // servido en el origen de la app = XSS almacenado con acceso a sesion + XSRF-TOKEN.
        String raw = row[1] == null ? "audio/ogg" : row[1].split(";")[0].trim().toLowerCase();
        String mime = switch (raw) {
            case "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/m4a",
                 "audio/wav", "audio/x-wav", "audio/webm", "audio/ogg", "audio/amr", "audio/3gpp" -> raw;
            default -> "audio/ogg";
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .header("Content-Disposition", "inline; filename=\"pedido-" + id + ".ogg\"")
                .contentType(MediaType.parseMediaType(mime))
                .body(new ByteArrayResource(storage.load(row[0])));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<ByteArrayResource> image(@PathVariable long id) {
        var row = jdbc.sql("SELECT storage_key, mime_type FROM media_image WHERE order_id = :o ORDER BY id DESC LIMIT 1")
                .param("o", id).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).optional()
                .orElseThrow(() -> new ApiException(404, "IMAGE_NOT_FOUND", "El pedido no tiene imagen adjunta"));
        if (row[0] == null) throw new ApiException(404, "IMAGE_NOT_FOUND", "La imagen aún no está disponible");
        String raw = row[1] == null ? "image/jpeg" : row[1].split(";")[0].trim().toLowerCase();
        String mime = switch (raw) {
            case "image/jpeg", "image/jpg", "image/png", "image/webp" -> raw;
            default -> "image/jpeg";
        };
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'")
                .header("Content-Disposition", "inline; filename=\"pedido-" + id + ".jpg\"")
                .contentType(MediaType.parseMediaType(mime))
                .body(new ByteArrayResource(storage.load(row[0])));
    }

    @PostMapping("/upload-photo")
    public java.util.Map<String, Object> uploadPhoto(
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String caption,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        if (file.getSize() > 20 * 1024 * 1024) throw new ApiException(413, "FILE_TOO_LARGE", "Imagen demasiado grande (máx 20MB)");
        String p = (phone != null && !phone.isBlank()) ? phone.strip() : "573000000000";
        String n = (name != null && !name.isBlank()) ? name.strip() : "Cliente Foto";
        String mime = file.getContentType() != null ? file.getContentType() : "image/jpeg";
        String extId = "photo-" + java.util.UUID.randomUUID();
        ingest.ingestImageBytes(p, n, extId, file.getBytes(), mime, caption);
        return java.util.Map.of("status", "accepted", "phone", p);
    }
}
