package com.fruver.infrastructure.printing;

import com.fruver.application.port.PrintStrategy;
import com.fruver.application.service.PrinterConfigService;
import com.fruver.application.service.TicketData;
import com.fruver.config.AppProperties;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class RpiSshPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(RpiSshPrintStrategy.class);
    private static final Pattern SAFE_ARG_PATTERN = Pattern.compile("^[a-zA-Z0-9._/-]+$");

    private final PrinterConfigService printerConfig;
    private final TalonarioRasterService talonarioRaster;
    private final AppProperties props;

    public record TestResult(boolean success, String message, long durationMs) {}

    public RpiSshPrintStrategy(PrinterConfigService printerConfig,
                               TalonarioRasterService talonarioRaster,
                               AppProperties props) {
        this.printerConfig = printerConfig;
        this.talonarioRaster = talonarioRaster;
        this.props = props;
    }

    @Override
    public String getStrategyName() {
        return "RPI_SSH";
    }

    @Override
    public void print(TicketData ticket, String location) throws Exception {
        PrinterConfigService.PrinterConfig cfg = printerConfig.get();
        log.info("Imprimiendo orden #{} vía Raspberry Pi SSH en {}:{} ({})", 
                ticket.orderNumber(), cfg.rpiHost(), cfg.rpiPort(), location);

        byte[] payload;
        String destType = cfg.rpiDestinationType() != null ? cfg.rpiDestinationType().toUpperCase() : "CUPS";
        if ("CUPS".equals(destType)) {
            // Para Brother HL-L2405W en CUPS, renderizamos el raster oficial del talonario
            payload = talonarioRaster.renderTicketToPwgRaster(ticket);
        } else {
            // Para tiqueteras térmicas directas por USB (/dev/usb/lp0) o socket TCP
            payload = EscPosEncoder.encode(com.fruver.application.service.TicketFormatter.format(ticket, props.printing().columns()));
        }

        executeRemotePrint(cfg, payload);
        log.info("✓ Orden #{} enviada exitosamente vía Raspberry Pi SSH a {}", ticket.orderNumber(), destType);
    }

    public TestResult testConnection() {
        PrinterConfigService.PrinterConfig cfg = printerConfig.get();
        return testConnectionWithConfig(cfg);
    }

    public TestResult testPrintSample() {
        PrinterConfigService.PrinterConfig cfg = printerConfig.get();
        long start = System.currentTimeMillis();
        try {
            String sampleText = "\n================================\n"
                    + "   FRUVER COPILOT - TEST RPI\n"
                    + " Impresion de Prueba Exitosa\n"
                    + " Host: " + cfg.rpiHost() + "\n"
                    + " Destino: " + cfg.rpiDestinationType() + "\n"
                    + "================================\n\n\n";
            byte[] payload = sampleText.getBytes(StandardCharsets.UTF_8);
            executeRemotePrint(cfg, payload);
            long elapsed = System.currentTimeMillis() - start;
            return new TestResult(true, "Prueba de impresión enviada exitosamente a la RPi (" + elapsed + " ms)", elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("Fallo al imprimir prueba en RPi: {}", e.getMessage());
            return new TestResult(false, "Error al imprimir: " + e.getMessage(), elapsed);
        }
    }

    public static TestResult testConnectionWithConfig(PrinterConfigService.PrinterConfig cfg) {
        long start = System.currentTimeMillis();
        Session session = null;
        ChannelExec channel = null;
        try {
            session = createSshSession(cfg);
            session.connect(7000); // 7s timeout

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand("uname -a; uptime");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.connect(5000);

            long waitStart = System.currentTimeMillis();
            while (!channel.isClosed() && (System.currentTimeMillis() - waitStart < 5000)) {
                Thread.sleep(100);
            }

            long elapsed = System.currentTimeMillis() - start;
            String response = out.toString(StandardCharsets.UTF_8).trim();
            return new TestResult(true, "Conectado a Raspberry Pi con éxito: " + response, elapsed);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            return new TestResult(false, "No se pudo conectar a la RPi: " + e.getMessage(), elapsed);
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private void executeRemotePrint(PrinterConfigService.PrinterConfig cfg, byte[] payload) throws Exception {
        String destType = cfg.rpiDestinationType() != null ? cfg.rpiDestinationType().toUpperCase() : "CUPS";
        String remoteCommand;

        if ("USB".equals(destType)) {
            String device = cfg.rpiUsbDevice() != null && !cfg.rpiUsbDevice().isBlank() ? cfg.rpiUsbDevice() : "/dev/usb/lp0";
            validateSafeArg(device, "Dispositivo USB");
            remoteCommand = "cat > " + device;
        } else if ("TCP".equals(destType)) {
            String tcpHost = cfg.rpiTcpHost() != null && !cfg.rpiTcpHost().isBlank() ? cfg.rpiTcpHost() : "127.0.0.1";
            int tcpPort = cfg.rpiTcpPort() > 0 ? cfg.rpiTcpPort() : 9100;
            validateSafeArg(tcpHost, "Host TCP");
            remoteCommand = "nc -w 5 " + tcpHost + " " + tcpPort;
        } else {
            // Default: CUPS
            String queue = cfg.rpiCupsQueue() != null && !cfg.rpiCupsQueue().isBlank() ? cfg.rpiCupsQueue() : "Brother_HL_L2405W";
            validateSafeArg(queue, "Cola CUPS");
            remoteCommand = "lp -d " + queue + " -o raw";
        }

        Session session = null;
        ChannelExec channel = null;
        try {
            session = createSshSession(cfg);
            session.connect(10000);

            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(remoteCommand);

            OutputStream stdin = channel.getOutputStream();
            InputStream stderr = channel.getErrStream();

            channel.connect(7000);

            stdin.write(payload);
            stdin.flush();
            stdin.close();

            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int read;
            long waitStart = System.currentTimeMillis();
            while (!channel.isClosed() && (System.currentTimeMillis() - waitStart < 15000)) {
                while (stderr.available() > 0 && (read = stderr.read(buf)) != -1) {
                    errBuf.write(buf, 0, read);
                }
                Thread.sleep(100);
            }

            int exitStatus = channel.getExitStatus();
            if (exitStatus != 0 && exitStatus != -1) {
                String errStr = errBuf.toString(StandardCharsets.UTF_8).trim();
                throw new IllegalStateException("Comando remoto '" + remoteCommand + "' falló con código " + exitStatus + ": " + errStr);
            }
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private static Session createSshSession(PrinterConfigService.PrinterConfig cfg) throws Exception {
        JSch jsch = new JSch();

        String host = (cfg.rpiHost() != null && !cfg.rpiHost().isBlank()) ? cfg.rpiHost().trim() : "100.105.30.91";
        int port = cfg.rpiPort() > 0 ? cfg.rpiPort() : 22;
        String user = (cfg.rpiUser() != null && !cfg.rpiUser().isBlank()) ? cfg.rpiUser().trim() : "victor";
        String authType = cfg.rpiAuthType() != null ? cfg.rpiAuthType().toUpperCase() : "KEY";

        if ("PASSWORD".equals(authType)) {
            Session session = jsch.getSession(user, host, port);
            session.setPassword(cfg.rpiPassword() != null ? cfg.rpiPassword() : "");
            session.setConfig("StrictHostKeyChecking", "no");
            return session;
        }

        // Auth por Clave Privada
        String keyPath = resolveKeyPath(cfg.rpiKeyPath());
        if (keyPath != null && new File(keyPath).exists()) {
            jsch.addIdentity(keyPath);
        } else {
            // Intentar llaves por defecto
            String home = System.getProperty("user.home");
            String defaultEd25519 = home + File.separator + ".ssh" + File.separator + "id_ed25519_tailscale_print";
            if (new File(defaultEd25519).exists()) {
                jsch.addIdentity(defaultEd25519);
            }
            String defaultRsa = home + File.separator + ".ssh" + File.separator + "id_rsa";
            if (new File(defaultRsa).exists()) {
                jsch.addIdentity(defaultRsa);
            }
            String fallbackId = home + File.separator + ".ssh" + File.separator + "id_ed25519";
            if (new File(fallbackId).exists()) {
                jsch.addIdentity(fallbackId);
            }
        }

        Session session = jsch.getSession(user, host, port);
        session.setConfig("StrictHostKeyChecking", "no");
        return session;
    }

    private static String resolveKeyPath(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            if (new File(configuredPath).exists()) return configuredPath;
            // Si está dentro de Windows USERPROFILE
            String resolved = configuredPath.replace("~", System.getProperty("user.home"));
            if (new File(resolved).exists()) return resolved;
        }
        return null;
    }

    private static void validateSafeArg(String val, String fieldName) {
        if (val == null || !SAFE_ARG_PATTERN.matcher(val).matches()) {
            throw new IllegalArgumentException("Parámetro inválido o inseguro para " + fieldName + ": " + val);
        }
    }
}

