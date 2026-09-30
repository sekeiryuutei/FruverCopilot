package com.fruver.interfaces.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruver.application.service.MessageIngestService;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Adaptador de entrada de WhatsApp Cloud API. Verifica la firma HMAC-SHA256 y traduce el payload a llamadas
 * de aplicacion (ingestText / ingestAudioRef). El dominio no conoce el formato de Meta.
 */
@RestController
@RequestMapping("/webhook/whatsapp")
@ConditionalOnProperty(name = "app.whatsapp.enabled", havingValue = "true")
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    private final AppProperties props;
    private final MessageIngestService ingest;
    private final ObjectMapper mapper;

    public WhatsAppWebhookController(AppProperties props, MessageIngestService ingest, ObjectMapper mapper) {
        this.props = props;
        this.ingest = ingest;
        this.mapper = mapper;
    }

    @GetMapping
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String expected = props.whatsapp().verifyToken();
        if ("subscribe".equals(mode) && expected != null && !expected.isBlank() && expected.equals(token)) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(403).build();
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody String body,
                                        @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {
        if (!validSignature(body, signature)) return ResponseEntity.status(403).build();
        try {
            JsonNode root = mapper.readTree(body);
            for (JsonNode entry : root.path("entry")) {
                for (JsonNode change : entry.path("changes")) {
                    JsonNode value = change.path("value");
                    Map<String, String> names = new HashMap<>();
                    for (JsonNode c : value.path("contacts")) names.put(c.path("wa_id").asText(), c.path("profile").path("name").asText(null));
                    for (JsonNode m : value.path("messages")) handle(m, names);
                }
            }
        } catch (Exception e) {
            // Siempre 200: Meta reintenta agresivamente si respondemos error, y los duplicados ya estan cubiertos.
            log.error("No se pudo procesar el webhook de WhatsApp", e);
        }
        return ResponseEntity.ok().build();
    }

    private void handle(JsonNode m, Map<String, String> names) {
        String id = m.path("id").asText(null);
        String from = m.path("from").asText(null);
        String type = m.path("type").asText("");
        String name = names.get(from);
        switch (type) {
            case "text" -> ingest.ingestText(from, name, id, m.path("text").path("body").asText(""));
            case "audio" -> ingest.ingestAudioRef(from, name, id, m.path("audio").path("id").asText(),
                    m.path("audio").path("mime_type").asText("audio/ogg"));
            default -> ingest.ingestText(from, name, id, "[Mensaje de tipo " + type + " no soportado]"); // visible para el empleado
        }
    }

    private boolean validSignature(String body, String header) {
        String secret = props.whatsapp().appSecret();
        if (secret == null || secret.isBlank() || header == null) return false; // sin secreto configurado, se rechaza todo
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), header.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
