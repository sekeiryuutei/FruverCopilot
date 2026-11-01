package com.fruver.application.service;

import com.fruver.config.AppProperties;
import com.fruver.infrastructure.printing.EscPosEncoder;
import com.fruver.infrastructure.printing.IppClient;
import com.fruver.infrastructure.printing.TalonarioRasterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PrinterConfigService {

    private static final Logger log = LoggerFactory.getLogger(PrinterConfigService.class);

    public record PrinterConfig(
            String officeHost,
            int officePort,
            String officePrintType,
            String officeEmail,
            String homeHost,
            int homePort,
            String homePrintType,
            String homeEmail,
            boolean enabled,
            String mode,
            String activePrinter,
            String schedule,
            String scheduleOfficeStart,
            String scheduleOfficeEnd,
            int failedCount,
            int pendingCount,
            int orderGraceSeconds,
            int printTimeoutSeconds,
            boolean autoPrintWholesale,
            int customerDebounceSeconds,
            String smtpHost,
            int smtpPort,
            String smtpUser,
            String smtpPassword,
            boolean smtpSsl
    ) {}

    private final JdbcClient jdbc;
    private final AppProperties props;
    private final TalonarioRasterService talonarioRaster;

    public PrinterConfigService(JdbcClient jdbc, AppProperties props, TalonarioRasterService talonarioRaster) {
        this.jdbc = jdbc;
        this.props = props;
        this.talonarioRaster = talonarioRaster;
    }

    public PrinterConfig get() {
        var row = jdbc.sql("""
                SELECT office_host, office_port, office_print_type, office_email,
                       home_host, home_port, home_print_type, home_email,
                       enabled, mode, order_grace_seconds, print_timeout_seconds,
                       auto_print_wholesale, customer_debounce_seconds,
                       schedule_office_start, schedule_office_end,
                       smtp_host, smtp_port, smtp_user, smtp_password, smtp_ssl
                FROM printer_setting WHERE id = 'DEFAULT'
                """)
                .query((rs, n) -> new Object[]{
                        rs.getString(1),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getInt(6),
                        rs.getString(7),
                        rs.getString(8),
                        rs.getBoolean(9),
                        rs.getString(10),
                        rs.getObject(11) != null ? rs.getInt(11) : null,
                        rs.getObject(12) != null ? rs.getInt(12) : null,
                        rs.getObject(13) != null ? rs.getBoolean(13) : null,
                        rs.getObject(14) != null ? rs.getInt(14) : null,
                        rs.getString(15),
                        rs.getString(16),
                        rs.getString(17),
                        rs.getInt(18),
                        rs.getString(19),
                        rs.getString(20),
                        rs.getBoolean(21)
                }).optional();

        String officeHost = (props.printing() != null && props.printing().officeHost() != null) ? props.printing().officeHost() : "127.0.0.1";
        int officePort = (props.printing() != null && props.printing().officePort() > 0) ? props.printing().officePort() : 9100;
        String officePrintType = "EMAIL";
        String officeEmail = "23363927290@print.brother.com";

        String homeHost = (props.printing() != null && props.printing().homeHost() != null) ? props.printing().homeHost() : "127.0.0.1";
        int homePort = (props.printing() != null && props.printing().homePort() > 0) ? props.printing().homePort() : 9100;
        String homePrintType = "ESCPOS";
        String homeEmail = "";

        boolean enabled = (props.printing() != null && props.printing().enabled());
        String mode = "AUTO";
        int orderGraceSeconds = (props.printing() != null) ? props.printing().wholesaleGraceSeconds() : 300;
        int printTimeoutSeconds = 5;
        boolean autoPrintWholesale = (props.printing() != null) ? props.printing().autoPrintWholesale() : true;
        int customerDebounceSeconds = (props.conversation() != null) ? props.conversation().debounceSeconds() : 20;

        String scheduleStart = "04:00";
        String scheduleEnd = "19:00";

        String smtpHost = "smtp.gmail.com";
        int smtpPort = 465;
        String smtpUser = "citas.drjorgejimenez@gmail.com";
        String smtpPassword = "tbizucojkyxzoows";
        boolean smtpSsl = true;

        if (row.isPresent()) {
            Object[] data = row.get();
            if (data[0] != null) officeHost = (String) data[0];
            if (data[1] != null && ((Integer) data[1]) > 0) officePort = (Integer) data[1];
            if (data[2] != null) officePrintType = (String) data[2];
            if (data[3] != null) officeEmail = (String) data[3];

            if (data[4] != null) homeHost = (String) data[4];
            if (data[5] != null && ((Integer) data[5]) > 0) homePort = (Integer) data[5];
            if (data[6] != null) homePrintType = (String) data[6];
            if (data[7] != null) homeEmail = (String) data[7];

            if (data[8] != null) enabled = (Boolean) data[8];
            if (data[9] != null) mode = (String) data[9];
            if (data[10] != null) orderGraceSeconds = (Integer) data[10];
            if (data[11] != null && ((Integer) data[11]) > 0) printTimeoutSeconds = (Integer) data[11];
            if (data[12] != null) autoPrintWholesale = (Boolean) data[12];
            if (props.conversation() != null && props.conversation().debounceSeconds() == 0) {
                customerDebounceSeconds = 0;
            } else if (data[13] != null && ((Integer) data[13]) >= 0) {
                customerDebounceSeconds = (Integer) data[13];
            }

            if (data[14] != null) scheduleStart = (String) data[14];
            if (data[15] != null) scheduleEnd = (String) data[15];

            if (data[16] != null) smtpHost = (String) data[16];
            if (data[17] != null && ((Integer) data[17]) > 0) smtpPort = (Integer) data[17];
            if (data[18] != null) smtpUser = (String) data[18];
            if (data[19] != null) smtpPassword = (String) data[19];
            if (data[20] != null) smtpSsl = (Boolean) data[20];
        }

        String active = currentTargetPrinter(mode, scheduleStart, scheduleEnd);
        int failedCount = jdbc.sql("SELECT count(*) FROM sale WHERE print_status = 'PRINT_FAILED'").query(Integer.class).single();
        int pendingCount = jdbc.sql("SELECT count(*) FROM print_job WHERE status = 'PENDING'").query(Integer.class).single();

        String scheduleStr = scheduleStart + " - " + scheduleEnd + " OFICINA / " + scheduleEnd + " - " + scheduleStart + " CASA";

        return new PrinterConfig(
                officeHost,
                officePort,
                officePrintType,
                officeEmail,
                homeHost,
                homePort,
                homePrintType,
                homeEmail,
                enabled,
                mode,
                active,
                scheduleStr,
                scheduleStart,
                scheduleEnd,
                failedCount,
                pendingCount,
                orderGraceSeconds,
                printTimeoutSeconds,
                autoPrintWholesale,
                customerDebounceSeconds,
                smtpHost,
                smtpPort,
                smtpUser,
                smtpPassword,
                smtpSsl
        );
    }

    public PrinterConfig update(String officeHost, Integer officePort, String homeHost, Integer homePort, Boolean enabled, String mode) {
        PrinterConfig current = get();
        return update(officeHost, officePort, current.officePrintType(), current.officeEmail(),
                homeHost, homePort, current.homePrintType(), current.homeEmail(),
                enabled, mode, current.scheduleOfficeStart(), current.scheduleOfficeEnd(),
                current.orderGraceSeconds(), current.printTimeoutSeconds(), current.autoPrintWholesale(), current.customerDebounceSeconds());
    }

    public PrinterConfig update(String officeHost, Integer officePort, String homeHost, Integer homePort,
                                Boolean enabled, String mode,
                                Integer orderGraceSeconds, Integer printTimeoutSeconds, Boolean autoPrintWholesale) {
        PrinterConfig current = get();
        return update(officeHost, officePort, current.officePrintType(), current.officeEmail(),
                homeHost, homePort, current.homePrintType(), current.homeEmail(),
                enabled, mode, current.scheduleOfficeStart(), current.scheduleOfficeEnd(),
                orderGraceSeconds, printTimeoutSeconds, autoPrintWholesale, current.customerDebounceSeconds());
    }

    public PrinterConfig update(String officeHost, Integer officePort, String officePrintType, String officeEmail,
                                String homeHost, Integer homePort, String homePrintType, String homeEmail,
                                Boolean enabled, String mode,
                                String scheduleOfficeStart, String scheduleOfficeEnd,
                                Integer orderGraceSeconds, Integer printTimeoutSeconds, Boolean autoPrintWholesale,
                                Integer customerDebounceSeconds) {
        jdbc.sql("""
                INSERT INTO printer_setting(id, office_host, office_port, office_print_type, office_email,
                                            home_host, home_port, home_print_type, home_email,
                                            enabled, mode, schedule_office_start, schedule_office_end,
                                            order_grace_seconds, print_timeout_seconds, auto_print_wholesale, customer_debounce_seconds, updated_at)
                VALUES ('DEFAULT', :oh, :op, :opt, :oe, :hh, :hp, :hpt, :he, :en, :m, :sos, :soe, :ogs, :pts, :apw, :cds, now())
                ON CONFLICT (id) DO UPDATE SET
                    office_host = EXCLUDED.office_host,
                    office_port = EXCLUDED.office_port,
                    office_print_type = EXCLUDED.office_print_type,
                    office_email = EXCLUDED.office_email,
                    home_host = EXCLUDED.home_host,
                    home_port = EXCLUDED.home_port,
                    home_print_type = EXCLUDED.home_print_type,
                    home_email = EXCLUDED.home_email,
                    enabled = EXCLUDED.enabled,
                    mode = EXCLUDED.mode,
                    schedule_office_start = EXCLUDED.schedule_office_start,
                    schedule_office_end = EXCLUDED.schedule_office_end,
                    order_grace_seconds = EXCLUDED.order_grace_seconds,
                    print_timeout_seconds = EXCLUDED.print_timeout_seconds,
                    auto_print_wholesale = EXCLUDED.auto_print_wholesale,
                    customer_debounce_seconds = EXCLUDED.customer_debounce_seconds,
                    updated_at = now()
                """).param("oh", (officeHost != null && !officeHost.isBlank()) ? officeHost.trim() : "127.0.0.1")
                .param("op", (officePort != null && officePort > 0) ? officePort : 9100)
                .param("opt", (officePrintType != null && !officePrintType.isBlank()) ? officePrintType.trim().toUpperCase() : "EMAIL")
                .param("oe", (officeEmail != null && !officeEmail.isBlank()) ? officeEmail.trim() : "23363927290@print.brother.com")
                .param("hh", (homeHost != null && !homeHost.isBlank()) ? homeHost.trim() : "127.0.0.1")
                .param("hp", (homePort != null && homePort > 0) ? homePort : 9100)
                .param("hpt", (homePrintType != null && !homePrintType.isBlank()) ? homePrintType.trim().toUpperCase() : "ESCPOS")
                .param("he", (homeEmail != null) ? homeEmail.trim() : "")
                .param("en", enabled != null ? enabled : true)
                .param("m", (mode != null && !mode.isBlank()) ? mode.trim().toUpperCase() : "AUTO")
                .param("sos", (scheduleOfficeStart != null && !scheduleOfficeStart.isBlank()) ? scheduleOfficeStart.trim() : "04:00")
                .param("soe", (scheduleOfficeEnd != null && !scheduleOfficeEnd.isBlank()) ? scheduleOfficeEnd.trim() : "19:00")
                .param("ogs", (orderGraceSeconds != null && orderGraceSeconds >= 0) ? orderGraceSeconds : 300)
                .param("pts", (printTimeoutSeconds != null && printTimeoutSeconds >= 1) ? printTimeoutSeconds : 5)
                .param("apw", autoPrintWholesale != null ? autoPrintWholesale : true)
                .param("cds", (customerDebounceSeconds != null && customerDebounceSeconds >= 0) ? customerDebounceSeconds : 20)
                .update();

        return get();
    }

    public String currentTargetPrinter(String mode) {
        PrinterConfig cfg = get();
        return currentTargetPrinter(mode, cfg.scheduleOfficeStart(), cfg.scheduleOfficeEnd());
    }

    public String currentTargetPrinter(String mode, String startStr, String endStr) {
        if ("OFICINA".equalsIgnoreCase(mode) || "FRUVER".equalsIgnoreCase(mode)) return "OFICINA";
        if ("CASA".equalsIgnoreCase(mode)) return "CASA";

        ZoneId zone = ZoneId.of((props.timezone() != null && !props.timezone().isBlank()) ? props.timezone() : "America/Bogota");
        LocalTime now = LocalTime.now(zone);

        try {
            LocalTime start = LocalTime.parse(startStr);
            LocalTime end = LocalTime.parse(endStr);
            boolean isOffice = !now.isBefore(start) && now.isBefore(end);
            return isOffice ? "OFICINA" : "CASA";
        } catch (Exception e) {
            int hour = now.getHour();
            return (hour >= 4 && hour < 19) ? "OFICINA" : "CASA";
        }
    }

    public String hostFor(String location) {
        PrinterConfig cfg = get();
        if ("CASA".equalsIgnoreCase(location)) return cfg.homeHost();
        return cfg.officeHost();
    }

    public int portFor(String location) {
        PrinterConfig cfg = get();
        if ("CASA".equalsIgnoreCase(location)) return cfg.homePort();
        return cfg.officePort();
    }

    public void testPrint(String targetLocation) {
        String loc = (targetLocation != null && !targetLocation.isBlank()) ? targetLocation.toUpperCase() : get().activePrinter();
        PrinterConfig cfg = get();
        String type = "OFICINA".equalsIgnoreCase(loc) ? cfg.officePrintType() : cfg.homePrintType();

        if ("EMAIL".equalsIgnoreCase(type) || "CORREO".equalsIgnoreCase(type)) {
            testEmailPrint(loc, cfg);
            return;
        }

        String host = hostFor(loc);
        int port = portFor(loc);

        if (port == 631 || "IPP".equalsIgnoreCase(type)) {
            try {
                byte[] raster = talonarioRaster.renderTestPageToPwgRaster(loc, host, port);
                IppClient.printRaster(host, port, "Prueba_Fruver_IPP", raster);
                return;
            } catch (Exception e) {
                throw new RuntimeException("Error al imprimir por IPP: " + e.getMessage(), e);
            }
        }

        String nowStr = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        var lines = List.of(
                new TicketFormatter.TicketLine("================================", true),
                new TicketFormatter.TicketLine("        FRUVER EL OASIS", true),
                new TicketFormatter.TicketLine("      PRUEBA DE IMPRESORA", true),
                new TicketFormatter.TicketLine("================================", true),
                new TicketFormatter.TicketLine("Ubicacion : " + loc, false),
                new TicketFormatter.TicketLine("Destino   : " + host + ":" + port, false),
                new TicketFormatter.TicketLine("Fecha     : " + nowStr, false),
                new TicketFormatter.TicketLine("Estado    : CONEXION OK", true),
                new TicketFormatter.TicketLine("================================", true)
        );
        byte[] payload = EscPosEncoder.encode(lines);

        int timeoutSec = cfg.printTimeoutSeconds();
        if (timeoutSec <= 0) timeoutSec = 5;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.min(3000, timeoutSec * 1000));
            socket.setSoTimeout(timeoutSec * 1000);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException("No se pudo conectar con la impresora en " + loc + " (" + host + ":" + port + "): " + e.getMessage(), e);
        }
    }

    private void testEmailPrint(String loc, PrinterConfig cfg) {
        String targetEmail = "OFICINA".equalsIgnoreCase(loc) ? cfg.officeEmail() : cfg.homeEmail();
        if (targetEmail == null || targetEmail.isBlank()) {
            throw new RuntimeException("No hay correo configurado para " + loc);
        }

        try {
            org.springframework.mail.javamail.JavaMailSenderImpl mailSender = new org.springframework.mail.javamail.JavaMailSenderImpl();
            mailSender.setHost(cfg.smtpHost() != null ? cfg.smtpHost() : "smtp.gmail.com");
            mailSender.setPort(cfg.smtpPort() > 0 ? cfg.smtpPort() : 465);
            mailSender.setUsername(cfg.smtpUser() != null ? cfg.smtpUser() : "citas.drjorgejimenez@gmail.com");
            mailSender.setPassword(cfg.smtpPassword() != null ? cfg.smtpPassword() : "tbizucojkyxzoows");

            java.util.Properties props = mailSender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            if (cfg.smtpSsl()) {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.socketFactory.port", String.valueOf(mailSender.getPort()));
                props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }

            jakarta.mail.internet.MimeMessage message = mailSender.createMimeMessage();
            org.springframework.mail.javamail.MimeMessageHelper helper = new org.springframework.mail.javamail.MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(mailSender.getUsername(), "Fruver El Oasis");
            helper.setTo(targetEmail.trim());
            helper.setSubject("Prueba Impresion " + loc + " - " + System.currentTimeMillis());
            helper.setText("Prueba de conexion exitosa con Brother Email Print.");

            mailSender.send(message);
            log.info("✓ Correo de prueba enviado exitosamente a {}", targetEmail);
        } catch (Exception e) {
            throw new RuntimeException("Error al enviar correo de prueba a Brother Cloud (" + targetEmail + "): " + e.getMessage(), e);
        }
    }
}
