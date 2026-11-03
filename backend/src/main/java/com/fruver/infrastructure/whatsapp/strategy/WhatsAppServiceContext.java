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
        if (active == WhatsAppProvider.BOTH) {
            // En modo BOTH, por defecto delega a Baileys si se pide una sola
            return strategies.getOrDefault(WhatsAppProvider.BAILEYS_QR, strategies.get(WhatsAppProvider.META_CLOUD_API));
        }
        WhatsAppProviderStrategy strat = strategies.get(active);
        if (strat == null) {
            strat = strategies.get(WhatsAppProvider.BAILEYS_QR);
        }
        return strat;
    }

    @Override
    public DeliveryStatus sendText(String phone, String text) {
        return sendText(phone, text, null, null);
    }

    public DeliveryStatus sendText(String phone, String text, WhatsAppProvider targetProvider, String quotedExternalId) {
        if (targetProvider != null && strategies.containsKey(targetProvider)) {
            return strategies.get(targetProvider).sendText(phone, text, quotedExternalId);
        }
        WhatsAppProvider active = getActiveProvider();
        if (active == WhatsAppProvider.BOTH) {
            DeliveryStatus s1 = DeliveryStatus.LOCAL_ONLY;
            DeliveryStatus s2 = DeliveryStatus.LOCAL_ONLY;
            var baileys = strategies.get(WhatsAppProvider.BAILEYS_QR);
            var meta = strategies.get(WhatsAppProvider.META_CLOUD_API);
            if (baileys != null) {
                try { s1 = baileys.sendText(phone, text, quotedExternalId); } catch (Exception e) { log.debug("Envío Baileys en BOTH: {}", e.getMessage()); }
            }
            if (meta != null) {
                try { s2 = meta.sendText(phone, text, quotedExternalId); } catch (Exception e) { log.debug("Envío Meta en BOTH: {}", e.getMessage()); }
            }
            return (s1 == DeliveryStatus.SENT || s2 == DeliveryStatus.SENT) ? DeliveryStatus.SENT : DeliveryStatus.LOCAL_ONLY;
        }
        return getStrategy().sendText(phone, text, quotedExternalId);
    }

    @Override
    public DeliveryStatus sendImage(String phone, String imageUrl, String caption) {
        WhatsAppProvider active = getActiveProvider();
        if (active == WhatsAppProvider.BOTH) {
            DeliveryStatus s1 = DeliveryStatus.LOCAL_ONLY;
            DeliveryStatus s2 = DeliveryStatus.LOCAL_ONLY;
            var baileys = strategies.get(WhatsAppProvider.BAILEYS_QR);
            var meta = strategies.get(WhatsAppProvider.META_CLOUD_API);
            if (baileys != null) {
                try { s1 = baileys.sendImage(phone, imageUrl, caption); } catch (Exception ignored) {}
            }
            if (meta != null) {
                try { s2 = meta.sendImage(phone, imageUrl, caption); } catch (Exception ignored) {}
            }
            return (s1 == DeliveryStatus.SENT || s2 == DeliveryStatus.SENT) ? DeliveryStatus.SENT : DeliveryStatus.LOCAL_ONLY;
        }
        return getStrategy().sendImage(phone, imageUrl, caption);
    }

    public DeliveryStatus sendReaction(String phone, String messageId, String emoji) {
        WhatsAppProvider active = getActiveProvider();
        if (active == WhatsAppProvider.BOTH) {
            var baileys = strategies.get(WhatsAppProvider.BAILEYS_QR);
            var meta = strategies.get(WhatsAppProvider.META_CLOUD_API);
            if (baileys != null) {
                try { baileys.sendReaction(phone, messageId, emoji); } catch (Exception ignored) {}
            }
            if (meta != null) {
                try { meta.sendReaction(phone, messageId, emoji); } catch (Exception ignored) {}
            }
            return DeliveryStatus.SENT;
        }
        return getStrategy().sendReaction(phone, messageId, emoji);
    }

    public WhatsAppStatusDto getStatus() {
        WhatsAppProvider active = getActiveProvider();
        if (active == WhatsAppProvider.BOTH) {
            var bStat = strategies.get(WhatsAppProvider.BAILEYS_QR) != null
                    ? strategies.get(WhatsAppProvider.BAILEYS_QR).getStatus() : null;
            var mStat = strategies.get(WhatsAppProvider.META_CLOUD_API) != null
                    ? strategies.get(WhatsAppProvider.META_CLOUD_API).getStatus() : null;

            String bPhone = bStat != null ? bStat.phone() : null;
            String mPhone = mStat != null ? mStat.phone() : null;
            String combinedPhone = (bPhone != null ? "QR: +" + bPhone : "QR: no") + " | " + (mPhone != null ? "Meta: " + mPhone : "Meta: no");

            boolean bConn = bStat != null && "CONNECTED".equalsIgnoreCase(bStat.status());
            boolean mConn = mStat != null && "CONNECTED".equalsIgnoreCase(mStat.status());
            boolean bQr = bStat != null && "QR_READY".equalsIgnoreCase(bStat.status());

            String overallStatus = (bConn || mConn) ? "CONNECTED" : (bQr ? "QR_READY" : "DISCONNECTED");
            String qr = bStat != null ? bStat.qrCodeBase64() : null;

            return new WhatsAppStatusDto(WhatsAppProvider.BOTH.name(), overallStatus, combinedPhone, qr);
        }
        return getStrategy().getStatus();
    }

    public void logout() {
        var b = strategies.get(WhatsAppProvider.BAILEYS_QR);
        if (b != null) b.logout();
        var m = strategies.get(WhatsAppProvider.META_CLOUD_API);
        if (m != null) m.logout();
    }

    @Override
    public MediaFile download(String mediaId) {
        if (mediaId != null && (mediaId.startsWith("audio_") || mediaId.startsWith("image_") || mediaId.startsWith("img_"))) {
            var b = strategies.get(WhatsAppProvider.BAILEYS_QR);
            if (b != null) return b.downloadMedia(mediaId);
        }
        WhatsAppProvider active = getActiveProvider();
        if (active == WhatsAppProvider.BAILEYS_QR) {
            return strategies.get(WhatsAppProvider.BAILEYS_QR).downloadMedia(mediaId);
        }
        return strategies.get(WhatsAppProvider.META_CLOUD_API).downloadMedia(mediaId);
    }
}
