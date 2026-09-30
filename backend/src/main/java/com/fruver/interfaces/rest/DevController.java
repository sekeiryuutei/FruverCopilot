package com.fruver.interfaces.rest;

import com.fruver.application.service.MessageIngestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/** Simulador de WhatsApp para probar sin proveedor. Solo existe con DEV_ENDPOINTS=true. Nunca en produccion. */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "app.dev-endpoints", havingValue = "true")
public class DevController {

    public record SimBody(@NotBlank String phone, String name, @NotBlank String text) {}

    private final MessageIngestService ingest;

    public DevController(MessageIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/messages")
    public Map<String, String> message(@Valid @RequestBody SimBody b) {
        ingest.ingestText(b.phone(), b.name(), "sim-" + UUID.randomUUID(), b.text());
        return Map.of("status", "accepted");
    }

    @PostMapping("/audio")
    public Map<String, String> audio(@RequestParam String phone, @RequestParam(required = false) String name,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        ingest.ingestAudioBytes(phone, name, "sim-" + UUID.randomUUID(), file.getBytes(),
                file.getContentType() == null ? "audio/ogg" : file.getContentType());
        return Map.of("status", "accepted");
    }
}
