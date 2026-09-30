package com.fruver.application.port;

public interface MessagingGateway {
    enum DeliveryStatus { SENT, LOCAL_ONLY }

    /** @throws MessagingUnavailableException si el proveedor no pudo enviar. */
    DeliveryStatus sendText(String phone, String text);
}
