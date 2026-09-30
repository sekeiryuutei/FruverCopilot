package com.fruver.infrastructure.printing;

import com.fruver.application.port.ThermalPrinterService;
import com.fruver.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Impresora termica ESC/POS por red (LAN, normalmente puerto 9100 RAW). */
@Service
@ConditionalOnProperty(name = "app.printing.enabled", havingValue = "true")
public class NetworkEscPosPrinter implements ThermalPrinterService {

    private final String host;
    private final int port;

    public NetworkEscPosPrinter(AppProperties props) {
        if (!"ESC_POS".equalsIgnoreCase(props.printing().type())) {
            throw new IllegalStateException("PRINTER_TYPE no soportado: " + props.printing().type() + " (solo ESC_POS)");
        }
        this.host = props.printing().host();
        this.port = props.printing().port();
    }

    @Override
    public void print(byte[] payload) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new PrinterException("Impresora no disponible en " + host + ":" + port, e);
        }
    }
}
