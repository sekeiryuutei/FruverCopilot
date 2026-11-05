package com.fruver.infrastructure.whatsapp.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fruver.application.port.MediaDownloader;
import com.fruver.application.port.MessagingGateway;
import com.fruver.application.port.MessagingUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.Map;

@Component
public class BaileysBridgeStrategy implements WhatsAppProviderStrategy {

    private static final Logger log = LoggerFactory.getLogger(BaileysBridgeStrategy.class);

    private final RestClient http;
    private final String bridgeBaseUrl;

    public BaileysBridgeStrategy(
            RestClient.Builder builder,
            @Value("${app.whatsapp.bridge-url:http://whatsapp-bridge:3000}") String bridgeBaseUrl) {
        this.bridgeBaseUrl = bridgeBaseUrl;
        this.http = builder.baseUrl(bridgeBaseUrl).build();
    }

    @Override
    public WhatsAppProvider provider() {
        return WhatsAppProvider.BAILEYS_QR;
    }

    @Override
    public MessagingGateway.DeliveryStatus sendText(String phone, String text) {
        return sendText(phone, text, null);
    }

    @Override
    public MessagingGateway.DeliveryStatus sendText(String phone, String text, String quotedExternalId) {
        Map<String, Object> body = new HashMap<>();
        body.put("to", phone);
        body.put("text", text);
        if (quotedExternalId != null && !quotedExternalId.isBlank()) {
            body.put("quotedMessageId", quotedExternalId);
        }
        try {
            http.post().uri("/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return MessagingGateway.DeliveryStatus.SENT;
        } catch (Exception e) {
            log.error("Fallo al enviar texto a través del puente Baileys: {}", e.getMessage());
            throw new MessagingUnavailableException("No se pudo enviar texto por WhatsApp Baileys", e);
        }
    }

    @Override
    public MessagingGateway.DeliveryStatus sendReaction(String phone, String messageId, String emoji) {
        Map<String, Object> body = new HashMap<>();
        body.put("to", phone);
        body.put("reaction", emoji);
        if (messageId != null && !messageId.isBlank()) {
            body.put("messageId", messageId);
        }
        try {
            http.post().uri("/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return MessagingGateway.DeliveryStatus.SENT;
        } catch (Exception e) {
            log.warn("No se pudo enviar reacción por Baileys a {}: {}", phone, e.getMessage());
            return MessagingGateway.DeliveryStatus.FAILED;
        }
    }

    @Override
    public MessagingGateway.DeliveryStatus sendImage(String phone, String imageUrl, String caption) {
        Map<String, Object> body = new HashMap<>();
        body.put("to", phone);
        body.put("imageUrl", imageUrl);
        if (caption != null && !caption.isBlank()) {
            body.put("caption", caption);
        }
        try {
            http.post().uri("/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return MessagingGateway.DeliveryStatus.SENT;
        } catch (Exception e) {
            log.error("Fallo al enviar imagen por Baileys: {}", e.getMessage());
            return sendText(phone, (caption != null ? caption + "\n" : "") + imageUrl);
        }
    }

    @Override
    public WhatsAppStatusDto getStatus() {
        try {
            JsonNode res = http.get().uri("/status").retrieve().body(JsonNode.class);
            if (res != null) {
                String st = res.path("status").asText("DISCONNECTED");
                String ph = res.path("phone").asText(null);
                String qr = res.path("qr").asText(null);
                return new WhatsAppStatusDto(WhatsAppProvider.BAILEYS_QR.name(), st, ph, qr);
            }
        } catch (Exception e) {
            log.debug("No se pudo conectar al microservicio whatsapp-bridge: {}", e.getMessage());
        }
        return new WhatsAppStatusDto(WhatsAppProvider.BAILEYS_QR.name(), "DISCONNECTED", null, null);
    }

    @Override
    public void logout() {
        try {
            http.post().uri("/logout").retrieve().toBodilessEntity();
            log.info("Sesión de Baileys cerrada con éxito");
        } catch (Exception e) {
            log.error("Error al cerrar sesión en Baileys: {}", e.getMessage());
        }
    }

    @Override
    public MediaDownloader.MediaFile downloadMedia(String mediaId) {
        try {
            ResponseEntity<byte[]> response = http.get()
                    .uri("/media/{id}", mediaId)
                    .retrieve()
                    .toEntity(byte[].class);

            byte[] data = response.getBody();
            if (data == null || data.length == 0) {
                throw new IllegalStateException("Archivo multimedia vacío recibido desde Baileys");
            }
            String contentType = response.getHeaders().getContentType() != null
                    ? response.getHeaders().getContentType().toString()
                    : "audio/ogg";
            return new MediaDownloader.MediaFile(data, contentType);
        } catch (Exception e) {
            throw new IllegalStateException("Error descargando media desde el puente Baileys: " + e.getMessage(), e);
        }
    }
}

