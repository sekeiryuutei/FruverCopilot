package com.fruver.infrastructure.whatsapp;

import com.fruver.application.port.MediaDownloader;
import com.fruver.application.port.MessagingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Modo sin WhatsApp (desarrollo / pruebas): el mensaje se registra en la conversacion pero no sale a ninguna red. */
@Service
@ConditionalOnProperty(name = "app.whatsapp.enabled", havingValue = "false", matchIfMissing = true)
public class LocalMessagingGateway implements MessagingGateway, MediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(LocalMessagingGateway.class);

    @Override
    public DeliveryStatus sendText(String phone, String text) {
        log.info("[WhatsApp deshabilitado] mensaje NO enviado a un cliente real ({} caracteres)", text.length());
        return DeliveryStatus.LOCAL_ONLY;
    }

    @Override
    public DeliveryStatus sendImage(String phone, String imageUrl, String caption) {
        log.info("[WhatsApp deshabilitado] imagen NO enviada a un cliente real: {} ({})", imageUrl, caption);
        return DeliveryStatus.LOCAL_ONLY;
    }

    @Override
    public MediaFile download(String mediaId) {
        throw new UnsupportedOperationException("WhatsApp esta deshabilitado");
    }
}
