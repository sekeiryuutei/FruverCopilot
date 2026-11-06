package com.fruver.infrastructure.printing;

import com.fruver.application.service.BlockingAlertService;
import com.fruver.application.service.PrinterConfigService;
import jakarta.mail.*;
import jakarta.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.Properties;

/**
 * Servicio que sondea e interpreta las respuestas y notificaciones enviadas por
 * el servicio de Brother Cloud (FromBrotherDevice@brother.com) y servidores de correo (rebotes).
 * Si detecta fallos, rechazos o problemas de hardware reportados por la impresora,
 * emite una alerta bloqueante inmediata al panel de administración.
 */
@Service
public class BrotherFeedbackMonitorService {

    private static final Logger log = LoggerFactory.getLogger(BrotherFeedbackMonitorService.class);

    public enum FeedbackType {
        CONFIRMED,
        FAILED,
        WARNING,
        IGNORED
    }

    public record FeedbackResult(FeedbackType type, String summary, String detail) {}

    private final PrinterConfigService printerConfig;
    private final BlockingAlertService alertService;
    private Instant lastCheck = Instant.now().minusSeconds(120);

    public BrotherFeedbackMonitorService(
            PrinterConfigService printerConfig,
            @Autowired(required = false) BlockingAlertService alertService
    ) {
        this.printerConfig = printerConfig;
        this.alertService = alertService;
    }

    /**
     * Evalúa el remitente, asunto y cuerpo de un correo electrónico para clasificar
     * si corresponde a una confirmación, error o rebote del servicio de impresión.
     */
    public static FeedbackResult evaluateMessage(String from, String subject, String body) {
        String safeFrom = from != null ? from.toLowerCase() : "";
        String safeSubj = subject != null ? subject.toLowerCase() : "";
        String safeBody = body != null ? body.toLowerCase() : "";

        // 1. Rebotes de servidor SMTP / Mailer-Daemon
        if (safeFrom.contains("mailer-daemon") || safeFrom.contains("postmaster")
                || safeSubj.contains("failure") || safeSubj.contains("undelivered") || safeSubj.contains("rejected")) {
            return new FeedbackResult(
                    FeedbackType.FAILED,
                    "Rebote de entrega en correo de impresora",
                    "El servidor de correo rebotó la factura. Asunto: " + (subject != null ? subject : "Sin asunto")
            );
        }

        // 2. Notificaciones de Brother Cloud
        if (safeFrom.contains("brother.com") || safeSubj.contains("brother") || safeBody.contains("brother")) {
            // Detección de fallos específicos
            if (safeBody.contains("no se pudo imprimir") || safeBody.contains("error al imprimir")
                    || safeBody.contains("rechazado") || safeBody.contains("formato no admitido")
                    || safeBody.contains("no compatible") || safeBody.contains("sin papel")
                    || safeBody.contains("atasco de papel") || safeBody.contains("fuera de línea")
                    || safeSubj.contains("error") || safeSubj.contains("fallo")) {
                return new FeedbackResult(
                        FeedbackType.FAILED,
                        "Fallo reportado por Brother Cloud",
                        "La impresora Brother Cloud reportó un error de impresión o hardware."
                );
            }

            // Confirmación de recepción / procesamiento
            if (safeSubj.contains("trabajo de impresión") || safeBody.contains("preparándose para imprimirse")
                    || safeBody.contains("recibido") || safeBody.contains("completada") || safeBody.contains("éxito")) {
                return new FeedbackResult(
                        FeedbackType.CONFIRMED,
                        "Recepción confirmada por Brother Cloud",
                        "El trabajo fue aceptado por los servidores de Brother Cloud."
                );
            }
        }

        return new FeedbackResult(FeedbackType.IGNORED, "No relevante", "");
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 30000)
    public void pollBrotherInbox() {
        if (printerConfig == null) {
            return;
        }
        PrinterConfigService.PrinterConfig cfg;
        try {
            cfg = printerConfig.get();
        } catch (Exception e) {
            return;
        }
        if (cfg == null || cfg.smtpUser() == null || cfg.smtpUser().isBlank() || cfg.smtpPassword() == null || cfg.smtpPassword().isBlank()) {
            return;
        }

        String printType = cfg.officePrintType();
        if (printType == null || !printType.toUpperCase().contains("CORREO")) {
            return;
        }

        String host = (cfg.smtpHost() != null && cfg.smtpHost().contains("gmail")) ? "imap.gmail.com" : "imap.gmail.com";
        int port = 993;

        Properties props = new Properties();
        props.put("mail.store.protocol", "imaps");
        props.put("mail.imaps.host", host);
        props.put("mail.imaps.port", String.valueOf(port));
        props.put("mail.imaps.ssl.enable", "true");
        props.put("mail.imaps.ssl.trust", "*");
        props.put("mail.imaps.timeout", "8000");
        props.put("mail.imaps.connectiontimeout", "8000");

        Store store = null;
        Folder inbox = null;
        try {
            Session session = Session.getInstance(props, null);
            store = session.getStore("imaps");
            store.connect(host, port, cfg.smtpUser(), cfg.smtpPassword());

            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Date searchSince = Date.from(lastCheck.minusSeconds(60));
            SearchTerm term = new SearchTerm() {
                @Override
                public boolean match(Message msg) {
                    try {
                        Date sentDate = msg.getSentDate();
                        Date recDate = msg.getReceivedDate();
                        Date effective = recDate != null ? recDate : sentDate;
                        return effective != null && effective.after(searchSince);
                    } catch (Exception e) {
                        return false;
                    }
                }
            };

            Message[] messages = inbox.search(term);
            Instant now = Instant.now();

            for (Message msg : messages) {
                try {
                    String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? msg.getFrom()[0].toString() : "";
                    String subject = msg.getSubject() != null ? msg.getSubject() : "";
                    String body = extractMessageText(msg);

                    FeedbackResult res = evaluateMessage(from, subject, body);

                    if (res.type() == FeedbackType.FAILED) {
                        log.warn(" Brother Feedback: Error detectado en correo - {} - {}", res.summary(), res.detail());
                        if (alertService != null) {
                            String alertId = "BROTHER_FEEDBACK_" + (msg.getMessageNumber());
                            alertService.raise(alertId, "CRITICAL", res.summary(), res.detail(), null, null);
                        }
                    } else if (res.type() == FeedbackType.CONFIRMED) {
                        log.info("✓ Brother Feedback: Confirmación recibida - {}", res.summary());
                    }
                } catch (Exception ex) {
                    log.debug("Error procesando mensaje individual de correo: {}", ex.getMessage());
                }
            }

            lastCheck = now;
        } catch (Exception e) {
            log.debug("No se pudo consultar el buzón IMAP de la impresora: {}", e.getMessage());
        } finally {
            try {
                if (inbox != null && inbox.isOpen()) inbox.close(false);
                if (store != null && store.isConnected()) store.close();
            } catch (Exception ignored) {}
        }
    }

    private static String extractMessageText(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            return (String) part.getContent();
        } else if (part.isMimeType("text/html")) {
            return (String) part.getContent();
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mp.getCount(); i++) {
                sb.append(extractMessageText(mp.getBodyPart(i))).append("\n");
            }
            return sb.toString();
        }
        return "";
    }
}
