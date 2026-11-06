package com.fruver.infrastructure.printing;

import com.fruver.application.port.PrintStrategy;
import com.fruver.application.service.BlockingAlertService;
import com.fruver.application.service.PrinterConfigService;
import com.fruver.application.service.TicketData;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Properties;

@Component
public class BrotherEmailPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(BrotherEmailPrintStrategy.class);
    public static final long MAX_PDF_BYTES = 20L * 1024 * 1024; // 20 MB límite estricto de Brother Cloud

    private final TalonarioRasterService talonarioRaster;
    private final PrinterConfigService printerConfig;
    private final BlockingAlertService alertService;

    public BrotherEmailPrintStrategy(
            TalonarioRasterService talonarioRaster,
            PrinterConfigService printerConfig,
            @Autowired(required = false) BlockingAlertService alertService
    ) {
        this.talonarioRaster = talonarioRaster;
        this.printerConfig = printerConfig;
        this.alertService = alertService;
    }

    @Override
    public String getStrategyName() {
        return "CORREO_BROTHER";
    }

    @Override
    public void print(TicketData ticket, String location) throws Exception {
        PrinterConfigService.PrinterConfig cfg = printerConfig.get();

        String targetEmail;
        if ("OFICINA".equalsIgnoreCase(location) || "FRUVER".equalsIgnoreCase(location)) {
            targetEmail = cfg.officeEmail();
        } else {
            targetEmail = cfg.homeEmail();
        }

        if (targetEmail == null || targetEmail.isBlank()) {
            throw new IllegalStateException("No hay un correo electrónico de impresora configurado para " + location);
        }

        log.info("Generando PDF talonario A5 para orden #{}...", ticket.orderNumber());
        byte[] pdfBytes = talonarioRaster.renderTicketToA4CenteredPdf(ticket);
        validatePdfSize(ticket.orderNumber(), pdfBytes != null ? pdfBytes.length : 0);

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost(cfg.smtpHost() != null ? cfg.smtpHost() : "smtp.gmail.com");
        mailSender.setPort(cfg.smtpPort() > 0 ? cfg.smtpPort() : 465);
        mailSender.setUsername(cfg.smtpUser() != null ? cfg.smtpUser() : "citas.drjorgejimenez@gmail.com");
        mailSender.setPassword(cfg.smtpPassword() != null ? cfg.smtpPassword() : "tbizucojkyxzoows");

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        if (cfg.smtpSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(mailSender.getPort()));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");

        long ts = System.currentTimeMillis();
        String filename = String.format("factura_%06d_%d.pdf", ticket.orderNumber(), ts);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(mailSender.getUsername(), "Fruver El Oasis");
        helper.setTo(targetEmail.trim());
        helper.setSubject("Factura " + ticket.orderNumber() + " - " + ts);
        helper.setText("", false); // Cuerpo vacío para que Brother no imprima carátula de texto
        helper.addAttachment(filename, new ByteArrayResource(pdfBytes), "application/pdf");

        log.info("Despachando correo de impresión a Brother Cloud ({}) para orden #{}...", targetEmail, ticket.orderNumber());
        mailSender.send(message);
        log.info("✓ Orden #{} enviada exitosamente a Brother Cloud ({})", ticket.orderNumber(), targetEmail);
    }

    public void validatePdfSize(long orderNumber, long sizeBytes) {
        if (sizeBytes > MAX_PDF_BYTES) {
            String errorMsg = String.format("El PDF generado (%.2f MB) excede el tamaño máximo permitido de 20 MB para Brother Cloud",
                    sizeBytes / (1024.0 * 1024.0));
            log.error("Orden #{}: {}", orderNumber, errorMsg);
            if (alertService != null) {
                alertService.raise("PDF_OVERSIZED_" + orderNumber, "CRITICAL",
                        "Factura excede límite de 20 MB", errorMsg, orderNumber, null);
            }
            throw new IllegalStateException(errorMsg);
        }
    }
}

