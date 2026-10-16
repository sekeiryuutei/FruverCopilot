package com.fruver.interfaces.rest;

import com.fruver.application.service.ApiException;
import com.fruver.application.service.MessageIngestService;
import com.fruver.config.DemoDataSeeder;
import org.springframework.beans.factory.ObjectProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    public record SimBody(
            @NotBlank @Size(max = 20) @Pattern(regexp = "^[+]?[0-9\\s\\-()]{5,20}$", message = "Telefono invalido") String phone,
            @Size(max = 120) String name,
            @NotBlank @Size(max = 1000) String text) {}

    private final MessageIngestService ingest;
    private final ObjectProvider<DemoDataSeeder> seeder;

    public DevController(MessageIngestService ingest, ObjectProvider<DemoDataSeeder> seeder) {
        this.ingest = ingest;
        this.seeder = seeder;
    }

    /** Restaura los pedidos demo 10003-10005 (p. ej. tras aprobar el 10005 en plena demostracion). */
    @PostMapping("/demo/reset")
    public Map<String, String> resetDemo() {
        DemoDataSeeder s = seeder.getIfAvailable();
        if (s == null) throw new ApiException(404, "DEMO_DISABLED", "Activa SEED_DEMO_DATA=true y reinicia el backend");
        s.seed(true);
        return Map.of("status", "reset");
    }

    @PostMapping({"/messages", "/whatsapp"})
    public Map<String, String> message(@Valid @RequestBody SimBody b) {
        ingest.ingestText(b.phone(), b.name(), "sim-" + UUID.randomUUID(), b.text());
        return Map.of("status", "accepted");
    }

    @PostMapping("/audio")
    public Map<String, String> audio(@RequestParam @Size(max = 20) @Pattern(regexp = "^[+]?[0-9\\s\\-()]{5,20}$") String phone,
                                     @RequestParam(required = false) @Size(max = 120) String name,
                                     @RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > 20 * 1024 * 1024) throw new ApiException(413, "FILE_TOO_LARGE", "Audio demasiado grande (max 20MB)");
        ingest.ingestAudioBytes(phone, name, "sim-" + UUID.randomUUID(), file.getBytes(),
                file.getContentType() == null ? "audio/ogg" : file.getContentType());
        return Map.of("status", "accepted");
    }
}
