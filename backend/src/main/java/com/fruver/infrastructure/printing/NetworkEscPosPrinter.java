package com.fruver.infrastructure.printing;

import com.fruver.application.port.ThermalPrinterService;
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

    public NetworkEscPosPrinter(AppProperties props) {
        if (!"ESC_POS".equalsIgnoreCase(props.printing().type())) {
            throw new IllegalStateException("PRINTER_TYPE no soportado: " + props.printing().type() + " (solo ESC_POS)");
        }
        this.props = props;
    }

    @Override
    public void print(byte[] payload, String targetLocation) {
        String loc = (targetLocation != null && !targetLocation.isBlank())
                ? targetLocation.toUpperCase()
                : props.printing().currentTargetPrinter(props.timezone());

        String host = props.printing().hostFor(loc);
        int port = props.printing().portFor(loc);

        log.info("Enviando impresión ({}) a {}:{}", loc, host, port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
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
