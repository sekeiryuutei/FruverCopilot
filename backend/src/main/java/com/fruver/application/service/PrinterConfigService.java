package com.fruver.application.service;

import com.fruver.config.AppProperties;
import com.fruver.infrastructure.printing.EscPosEncoder;
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
            int pendingCount
    ) {}

    private final JdbcClient jdbc;
    private final AppProperties props;

    public PrinterConfigService(JdbcClient jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public PrinterConfig get() {
        var row = jdbc.sql("SELECT office_host, office_port, home_host, home_port, enabled, mode FROM printer_setting WHERE id = 'DEFAULT'")
                .query((rs, n) -> new Object[]{
                        rs.getString(1),
                        rs.getInt(2),
                        rs.getString(3),
                        rs.getInt(4),
                        rs.getBoolean(5),
                        rs.getString(6)
                }).optional();

        String officeHost = (props.printing() != null && props.printing().officeHost() != null) ? props.printing().officeHost() : "127.0.0.1";
        int officePort = (props.printing() != null && props.printing().officePort() > 0) ? props.printing().officePort() : 9100;
        String homeHost = (props.printing() != null && props.printing().homeHost() != null) ? props.printing().homeHost() : "127.0.0.1";
        int homePort = (props.printing() != null && props.printing().homePort() > 0) ? props.printing().homePort() : 9100;
        boolean enabled = (props.printing() != null && props.printing().enabled());
        String mode = "AUTO";

        if (row.isPresent()) {
            Object[] data = row.get();
            if (data[0] != null) officeHost = (String) data[0];
            if (data[1] != null && ((Integer) data[1]) > 0) officePort = (Integer) data[1];
            if (data[2] != null) homeHost = (String) data[2];
            if (data[3] != null && ((Integer) data[3]) > 0) homePort = (Integer) data[3];
            if (data[4] != null) enabled = (Boolean) data[4];
            if (data[5] != null) mode = (String) data[5];
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
                pendingCount
        );
    }

    public PrinterConfig update(String officeHost, Integer officePort, String homeHost, Integer homePort, Boolean enabled, String mode) {
        jdbc.sql("""
                INSERT INTO printer_setting(id, office_host, office_port, home_host, home_port, enabled, mode, updated_at)
                VALUES ('DEFAULT', :oh, :op, :hh, :hp, :en, :m, now())
                ON CONFLICT (id) DO UPDATE SET
                    office_host = EXCLUDED.office_host,
                    office_port = EXCLUDED.office_port,
                    home_host = EXCLUDED.home_host,
                    home_port = EXCLUDED.home_port,
                    enabled = EXCLUDED.enabled,
                    mode = EXCLUDED.mode,
                    updated_at = now()
                """).param("oh", (officeHost != null && !officeHost.isBlank()) ? officeHost.trim() : "127.0.0.1")
                .param("op", (officePort != null && officePort > 0) ? officePort : 9100)
                .param("hh", (homeHost != null && !homeHost.isBlank()) ? homeHost.trim() : "127.0.0.1")
                .param("hp", (homePort != null && homePort > 0) ? homePort : 9100)
                .param("en", enabled != null ? enabled : true)
                .param("m", (mode != null && !mode.isBlank()) ? mode.trim().toUpperCase() : "AUTO")
                .update();

        return get();
    }

    public String currentTargetPrinter(String mode) {
        if ("OFICINA".equalsIgnoreCase(mode)) return "OFICINA";
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

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(3500);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException("No se pudo conectar con la impresora en " + loc + " (" + host + ":" + port + "): " + e.getMessage(), e);
        }
    }
}
