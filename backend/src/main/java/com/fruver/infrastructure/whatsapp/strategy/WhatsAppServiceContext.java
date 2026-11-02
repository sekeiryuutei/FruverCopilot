package com.fruver.infrastructure.whatsapp.strategy;

import com.fruver.application.port.MediaDownloader;
import com.fruver.application.port.MessagingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
@Primary
public class WhatsAppServiceContext implements MessagingGateway, MediaDownloader {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppServiceContext.class);

    private final JdbcClient jdbc;
    private final Map<WhatsAppProvider, WhatsAppProviderStrategy> strategies = new EnumMap<>(WhatsAppProvider.class);

    public WhatsAppServiceContext(JdbcClient jdbc, List<WhatsAppProviderStrategy> strategyList) {
        this.jdbc = jdbc;
        for (var s : strategyList) {
            strategies.put(s.provider(), s);
        }
    }

    public WhatsAppProvider getActiveProvider() {
        try {
            String val = jdbc.sql("SELECT whatsapp_provider FROM printer_setting LIMIT 1")
                    .query(String.class)
                    .optional()
                    .orElse("BAILEYS_QR");
            return WhatsAppProvider.fromString(val);
        } catch (Exception e) {
            return WhatsAppProvider.BAILEYS_QR;
        }
    }

    public void setActiveProvider(WhatsAppProvider provider) {
        jdbc.sql("UPDATE printer_setting SET whatsapp_provider = :p")
                .param("p", provider.name())
                .update();
        log.info("Proveedor de WhatsApp actualizado a: {}", provider.name());
    }

    public WhatsAppProviderStrategy getStrategy() {
        WhatsAppProvider active = getActiveProvider();
        WhatsAppProviderStrategy strat = strategies.get(active);
        if (strat == null) {
            strat = strategies.get(WhatsAppProvider.BAILEYS_QR);
        }
        return strat;
    }

    @Override
    public DeliveryStatus sendText(String phone, String text) {
        return getStrategy().sendText(phone, text);
    }

    @Override
    public DeliveryStatus sendImage(String phone, String imageUrl, String caption) {
        return getStrategy().sendImage(phone, imageUrl, caption);
    }

    public DeliveryStatus sendReaction(String phone, String messageId, String emoji) {
        return getStrategy().sendReaction(phone, messageId, emoji);
    }

    public WhatsAppStatusDto getStatus() {
        return getStrategy().getStatus();
    }

    public void logout() {
        getStrategy().logout();
    }

    @Override
    public MediaFile download(String mediaId) {
        return getStrategy().downloadMedia(mediaId);
    }
}
