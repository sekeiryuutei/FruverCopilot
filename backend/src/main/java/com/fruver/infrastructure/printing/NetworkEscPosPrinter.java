package com.fruver.infrastructure.printing;

import com.fruver.application.port.ThermalPrinterService;
import com.fruver.application.service.PrinterConfigService;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Impresora termica ESC/POS por red (LAN, puerto RAW 9100).
 * Soporta enrutamiento dinamico de dos impresoras:
 * - OFICINA / FRUVER: 04:00 - 19:00 (America/Bogota)
 * - CASA: 19:00 - 04:00 (America/Bogota)
 */
@Service
@ConditionalOnProperty(name = "app.printing.enabled", havingValue = "true")
public class NetworkEscPosPrinter implements ThermalPrinterService {

    private static final Logger log = LoggerFactory.getLogger(NetworkEscPosPrinter.class);

    private final AppProperties props;
    private final PrinterConfigService printerConfig;

    public NetworkEscPosPrinter(AppProperties props, PrinterConfigService printerConfig) {
        if (!"ESC_POS".equalsIgnoreCase(props.printing().type())) {
            throw new IllegalStateException("PRINTER_TYPE no soportado: " + props.printing().type() + " (solo ESC_POS)");
        }
        this.props = props;
        this.printerConfig = printerConfig;
    }

    @Override
    public void print(byte[] payload, String targetLocation) {
        String loc = (targetLocation != null && !targetLocation.isBlank())
                ? targetLocation.toUpperCase()
                : printerConfig.get().activePrinter();

        String host = printerConfig.hostFor(loc);
        int port = printerConfig.portFor(loc);

        int timeoutSec = printerConfig.get().printTimeoutSeconds();
        if (timeoutSec <= 0) timeoutSec = 5;
        log.info("Enviando impresión ({}) a {}:{} (timeout: {}s)", loc, host, port, timeoutSec);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.min(3000, timeoutSec * 1000));
            socket.setSoTimeout(timeoutSec * 1000);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new PrinterException("Impresora (" + loc + ") no disponible en " + host + ":" + port, e);
        }
    }

    @Override
    public void print(byte[] payload) {
        print(payload, null);
    }
}
