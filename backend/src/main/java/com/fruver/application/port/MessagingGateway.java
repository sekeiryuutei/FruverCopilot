package com.fruver.application.port;

public interface MessagingGateway {
    enum DeliveryStatus { SENT, LOCAL_ONLY }

    /** @throws MessagingUnavailableException si el proveedor no pudo enviar. */
    DeliveryStatus sendText(String phone, String text);

    /** Envío de imagen por WhatsApp (o fallback a texto con el enlace). */
    default DeliveryStatus sendImage(String phone, String imageUrl, String caption) {
        return sendText(phone, (caption != null && !caption.isBlank() ? caption + "\n" : "") + imageUrl);
    }
}
