package com.fruver.infrastructure.whatsapp.strategy;

import com.fruver.application.port.MediaDownloader;
import com.fruver.application.port.MessagingGateway;

public interface WhatsAppProviderStrategy {
    WhatsAppProvider provider();

    MessagingGateway.DeliveryStatus sendText(String phone, String text);

    MessagingGateway.DeliveryStatus sendReaction(String phone, String messageId, String emoji);

    MessagingGateway.DeliveryStatus sendImage(String phone, String imageUrl, String caption);

    WhatsAppStatusDto getStatus();

    void logout();

    MediaDownloader.MediaFile downloadMedia(String mediaId);
}

