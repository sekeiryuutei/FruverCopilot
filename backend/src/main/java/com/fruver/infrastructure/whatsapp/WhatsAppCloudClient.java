package com.fruver.infrastructure.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fruver.application.port.MediaDownloader;
import com.fruver.application.port.MessagingGateway;
import com.fruver.application.port.MessagingUnavailableException;
import com.fruver.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.Map;

/**
 * Adaptador de WhatsApp Cloud API (Meta). Es el unico lugar que la conoce: cambiar de proveedor
 * (Twilio, 360dialog...) significa reemplazar esta clase y el webhook, no el dominio.
 * Nota: fuera de la ventana de 24 h Meta solo permite plantillas aprobadas; el envio fallara con 4xx.
 */
@Service
@ConditionalOnProperty(name = "app.whatsapp.enabled", havingValue = "true")
public class WhatsAppCloudClient implements MessagingGateway, MediaDownloader {

    private final RestClient http;
    private final String phoneNumberId;

    public WhatsAppCloudClient(RestClient.Builder builder, AppProperties props) {
        AppProperties.Whatsapp w = props.whatsapp();
        this.phoneNumberId = w.phoneNumberId();
        this.http = builder.baseUrl("https://graph.facebook.com/" + w.apiVersion())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + w.accessToken()).build();
    }

    @Override
    public DeliveryStatus sendText(String phone, String text) {
        Map<String, Object> body = Map.of("messaging_product", "whatsapp", "to", phone, "type", "text",
                "text", Map.of("body", text));
        try {
            http.post().uri("/{id}/messages", phoneNumberId).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().toBodilessEntity();
            return DeliveryStatus.SENT;
        } catch (RestClientException e) {
            throw new MessagingUnavailableException("No se pudo enviar por WhatsApp", e);
        }
    }

    @Override
    public MediaFile download(String mediaId) {
        JsonNode meta = http.get().uri("/{id}", mediaId).retrieve().body(JsonNode.class);
        if (meta == null || meta.path("url").asText("").isBlank()) throw new IllegalStateException("Media sin URL");
        byte[] data = http.get().uri(URI.create(meta.path("url").asText())).retrieve().body(byte[].class);
        if (data == null || data.length == 0) throw new IllegalStateException("Audio vacio");
        return new MediaFile(data, meta.path("mime_type").asText("audio/ogg"));
    }
}
