package com.fruver.infrastructure.printing;

import com.fruver.application.port.ThermalPrinterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** PRINTER_ENABLED=false: no hay impresora fisica; el ticket se escribe en el log para poder probar el flujo. */
@Service
@ConditionalOnProperty(name = "app.printing.enabled", havingValue = "false", matchIfMissing = true)
public class SimulatedPrinter implements ThermalPrinterService {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPrinter.class);

    @Override
    public void print(byte[] payload) {
        String text = new String(payload, StandardCharsets.US_ASCII).replaceAll("[\\x00-\\x09\\x0B-\\x1F]", "");
        log.info("[IMPRESORA SIMULADA]\n{}", text);
    }
}
