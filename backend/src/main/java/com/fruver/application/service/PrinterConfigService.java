package com.fruver.application.service;

import com.fruver.config.AppProperties;
import com.fruver.infrastructure.printing.EscPosEncoder;
import com.fruver.infrastructure.printing.IppClient;
import com.fruver.infrastructure.printing.TalonarioRasterService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Service
public class PrinterConfigService {

    public record PrinterConfig(
            String officeHost,
            int officePort,
            String homeHost,
            int homePort,
            boolean enabled,
            String mode,
            String activePrinter,
            String schedule,
            int failedCount,
            int pendingCount,
            int orderGraceSeconds,
            int printTimeoutSeconds,
            boolean autoPrintWholesale,
            int customerDebounceSeconds
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
        var row = jdbc.sql("SELECT office_host, office_port, home_host, home_port, enabled, mode, order_grace_seconds, print_timeout_seconds, auto_print_wholesale, customer_debounce_seconds FROM printer_setting WHERE id = 'DEFAULT'")
                .query((rs, n) -> new Object[]{
                        rs.getString(1),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getBoolean(5),
                        rs.getString(6),
                        rs.getObject(7) != null ? rs.getInt(7) : null,
                        rs.getObject(8) != null ? rs.getInt(8) : null,
                        rs.getObject(9) != null ? rs.getBoolean(9) : null,
                        rs.getObject(10) != null ? rs.getInt(10) : null
                }).optional();

        String officeHost = (props.printing() != null && props.printing().officeHost() != null) ? props.printing().officeHost() : "127.0.0.1";
        int officePort = (props.printing() != null && props.printing().officePort() > 0) ? props.printing().officePort() : 9100;
        String homeHost = (props.printing() != null && props.printing().homeHost() != null) ? props.printing().homeHost() : "127.0.0.1";
        int homePort = (props.printing() != null && props.printing().homePort() > 0) ? props.printing().homePort() : 9100;
        boolean enabled = (props.printing() != null && props.printing().enabled());
        String mode = "AUTO";
        int orderGraceSeconds = (props.printing() != null) ? props.printing().wholesaleGraceSeconds() : 300;
        int printTimeoutSeconds = 5;
        boolean autoPrintWholesale = (props.printing() != null) ? props.printing().autoPrintWholesale() : true;
        int customerDebounceSeconds = (props.conversation() != null) ? props.conversation().debounceSeconds() : 20;

        if (row.isPresent()) {
            Object[] data = row.get();
            if (data[0] != null) officeHost = (String) data[0];
            if (data[1] != null && ((Integer) data[1]) > 0) officePort = (Integer) data[1];
            if (data[2] != null) homeHost = (String) data[2];
            if (data[3] != null && ((Integer) data[3]) > 0) homePort = (Integer) data[3];
            if (data[4] != null) enabled = (Boolean) data[4];
            if (data[5] != null) mode = (String) data[5];
            if (data[6] != null) orderGraceSeconds = (Integer) data[6];
            if (data[7] != null && ((Integer) data[7]) > 0) printTimeoutSeconds = (Integer) data[7];
            if (data[8] != null) autoPrintWholesale = (Boolean) data[8];
            if (data[9] != null && ((Integer) data[9]) >= 0) customerDebounceSeconds = (Integer) data[9];
        }

        String active = currentTargetPrinter(mode);
        int failedCount = jdbc.sql("SELECT count(*) FROM sale WHERE print_status = 'PRINT_FAILED'").query(Integer.class).single();
        int pendingCount = jdbc.sql("SELECT count(*) FROM print_job WHERE status = 'PENDING'").query(Integer.class).single();

        return new PrinterConfig(
                officeHost,
                officePort,
                homeHost,
                homePort,
                enabled,
                mode,
                active,
                "04:00 - 19:00 OFICINA / 19:00 - 04:00 CASA",
                failedCount,
                pendingCount,
                orderGraceSeconds,
                printTimeoutSeconds,
                autoPrintWholesale,
                customerDebounceSeconds
        );
    }

    public PrinterConfig update(String officeHost, Integer officePort, String homeHost, Integer homePort, Boolean enabled, String mode) {
        PrinterConfig current = get();
        return update(officeHost, officePort, homeHost, homePort, enabled, mode,
                current.orderGraceSeconds(), current.printTimeoutSeconds(), current.autoPrintWholesale(), current.customerDebounceSeconds());
    }

    public PrinterConfig update(String officeHost, Integer officePort, String homeHost, Integer homePort,
                                Boolean enabled, String mode,
                                Integer orderGraceSeconds, Integer printTimeoutSeconds, Boolean autoPrintWholesale) {
        PrinterConfig current = get();
        return update(officeHost, officePort, homeHost, homePort, enabled, mode,
                orderGraceSeconds, printTimeoutSeconds, autoPrintWholesale, current.customerDebounceSeconds());
    }

    public PrinterConfig update(String officeHost, Integer officePort, String homeHost, Integer homePort,
                                Boolean enabled, String mode,
                                Integer orderGraceSeconds, Integer printTimeoutSeconds, Boolean autoPrintWholesale,
                                Integer customerDebounceSeconds) {
        jdbc.sql("""
                INSERT INTO printer_setting(id, office_host, office_port, home_host, home_port, enabled, mode,
                                            order_grace_seconds, print_timeout_seconds, auto_print_wholesale, customer_debounce_seconds, updated_at)
                VALUES ('DEFAULT', :oh, :op, :hh, :hp, :en, :m, :ogs, :pts, :apw, :cds, now())
                ON CONFLICT (id) DO UPDATE SET
                    office_host = EXCLUDED.office_host,
                    office_port = EXCLUDED.office_port,
                    home_host = EXCLUDED.home_host,
                    home_port = EXCLUDED.home_port,
                    enabled = EXCLUDED.enabled,
                    mode = EXCLUDED.mode,
                    order_grace_seconds = EXCLUDED.order_grace_seconds,
                    print_timeout_seconds = EXCLUDED.print_timeout_seconds,
                    auto_print_wholesale = EXCLUDED.auto_print_wholesale,
                    customer_debounce_seconds = EXCLUDED.customer_debounce_seconds,
                    updated_at = now()
                """).param("oh", (officeHost != null && !officeHost.isBlank()) ? officeHost.trim() : "127.0.0.1")
                .param("op", (officePort != null && officePort > 0) ? officePort : 9100)
                .param("hh", (homeHost != null && !homeHost.isBlank()) ? homeHost.trim() : "127.0.0.1")
                .param("hp", (homePort != null && homePort > 0) ? homePort : 9100)
                .param("en", enabled != null ? enabled : true)
                .param("m", (mode != null && !mode.isBlank()) ? mode.trim().toUpperCase() : "AUTO")
                .param("ogs", (orderGraceSeconds != null && orderGraceSeconds >= 0) ? orderGraceSeconds : 300)
                .param("pts", (printTimeoutSeconds != null && printTimeoutSeconds >= 1) ? printTimeoutSeconds : 5)
                .param("apw", autoPrintWholesale != null ? autoPrintWholesale : true)
                .param("cds", (customerDebounceSeconds != null && customerDebounceSeconds >= 0) ? customerDebounceSeconds : 20)
                .update();

        return get();
    }

    public String currentTargetPrinter(String mode) {
        if ("OFICINA".equalsIgnoreCase(mode) || "FRUVER".equalsIgnoreCase(mode)) return "OFICINA";
        if ("CASA".equalsIgnoreCase(mode)) return "CASA";
        ZoneId zone = ZoneId.of((props.timezone() != null && !props.timezone().isBlank()) ? props.timezone() : "America/Bogota");
        int hour = LocalTime.now(zone).getHour();
        return (hour >= 4 && hour < 19) ? "OFICINA" : "CASA";
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
        String host = hostFor(loc);
        int port = portFor(loc);

        if (port == 631) {
            try {
                byte[] raster = talonarioRaster.renderTestPageToPwgRaster(loc, host, port);
                IppClient.printRaster(host, port, "Prueba_Fruver_IPP", raster);
                return;
            } catch (Exception e) {
                throw new RuntimeException("Error al imprimir por IPP (puerto 631): " + e.getMessage(), e);
            }
        }

        String nowStr = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        var lines = java.util.List.of(
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

        int timeoutSec = get().printTimeoutSeconds();
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
}
