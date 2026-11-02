package com.fruver.infrastructure.whatsapp.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fruver.application.port.MediaDownloader;
import com.fruver.application.port.MessagingGateway;
import com.fruver.application.port.MessagingUnavailableException;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Map;

@Component
public class MetaCloudApiStrategy implements WhatsAppProviderStrategy {

    private static final Logger log = LoggerFactory.getLogger(MetaCloudApiStrategy.class);

    private final RestClient http;
    private final String phoneNumberId;
    private final String accessToken;

    public MetaCloudApiStrategy(RestClient.Builder builder, AppProperties props) {
        AppProperties.Whatsapp w = props.whatsapp();
        this.phoneNumberId = w.phoneNumberId();
        this.accessToken = w.accessToken();
        this.http = builder.baseUrl("https://graph.facebook.com/" + w.apiVersion())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (accessToken != null ? accessToken : ""))
                .build();
    }

    @Override
    public WhatsAppProvider provider() {
        return WhatsAppProvider.META_CLOUD_API;
    }

    @Override
    public MessagingGateway.DeliveryStatus sendText(String phone, String text) {
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", phone,
                "type", "text",
                "text", Map.of("body", text)
        );
        try {
            http.post().uri("/{id}/messages", phoneNumberId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return MessagingGateway.DeliveryStatus.SENT;
        } catch (RestClientException e) {
            throw new MessagingUnavailableException("No se pudo enviar texto por Meta Cloud API", e);
        }
    }

    @Override
    public MessagingGateway.DeliveryStatus sendReaction(String phone, String messageId, String emoji) {
        if (messageId == null || messageId.isBlank()) {
            return sendText(phone, emoji);
        }
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "recipient_type", "individual",
                "to", phone,
                "type", "reaction",
                "reaction", Map.of("message_id", messageId, "emoji", emoji)
        );
        try {
            http.post().uri("/{id}/messages", phoneNumberId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return MessagingGateway.DeliveryStatus.SENT;
        } catch (RestClientException e) {
            log.warn("Fallo al enviar reacción con Meta Cloud API: {}. Fallback a texto.", e.getMessage());
            return sendText(phone, emoji);
        }
    }

    @Override
    public MessagingGateway.DeliveryStatus sendImage(String phone, String imageUrl, String caption) {
        java.util.Map<String, Object> imgMap = new java.util.HashMap<>();
        imgMap.put("link", imageUrl);
        if (caption != null && !caption.isBlank()) imgMap.put("caption", caption);
        Map<String, Object> body = Map.of(
                "messaging_product", "whatsapp",
                "to", phone,
                "type", "image",
                "image", imgMap
        );
        try {
            http.post().uri("/{id}/messages", phoneNumberId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return MessagingGateway.DeliveryStatus.SENT;
        } catch (RestClientException e) {
            return sendText(phone, (caption != null && !caption.isBlank() ? caption + "\n" : "") + imageUrl);
        }
    }

    @Override
    public WhatsAppStatusDto getStatus() {
        boolean configured = accessToken != null && !accessToken.isBlank() && phoneNumberId != null && !phoneNumberId.isBlank();
        return new WhatsAppStatusDto(
                WhatsAppProvider.META_CLOUD_API.name(),
                configured ? "CONNECTED" : "DISCONNECTED",
                phoneNumberId != null ? phoneNumberId : "",
                null
        );
    }

    @Override
    public void logout() {
        log.info("Logout solicitado para Meta Cloud API (sin acción de desconexión remota necesaria).");
    }

    @Override
    public MediaDownloader.MediaFile downloadMedia(String mediaId) {
        try {
            JsonNode meta = http.get().uri("/{id}", mediaId).retrieve().body(JsonNode.class);
            if (meta == null || meta.path("url").asText("").isBlank()) throw new IllegalStateException("Media sin URL");
            byte[] data = http.get().uri(URI.create(meta.path("url").asText())).retrieve().body(byte[].class);
            if (data == null || data.length == 0) throw new IllegalStateException("Audio o archivo vacío");
            return new MediaDownloader.MediaFile(data, meta.path("mime_type").asText("audio/ogg"));
        } catch (Exception e) {
            throw new IllegalStateException("Error descargando media desde Meta: " + e.getMessage(), e);
        }
    }
}
