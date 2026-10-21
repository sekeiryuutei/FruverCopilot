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
    public void print(byte[] payload, String targetLocation) {
        String loc = targetLocation != null ? targetLocation : "OFICINA";
        String text = new String(payload, StandardCharsets.US_ASCII).replaceAll("[\\x00-\\x09\\x0B-\\x1F]", "");
        // No volcar PII (nombre/telefono/direccion) al log: enmascara lineas sensibles (Ley 1581).
        String safe = text.replaceAll("(?m)^(.*(Tel|Cliente|Direcci[oó]n|Cliente:|Tel:).{0,40})$", "$1 [oculto]");
        if (safe.length() > 2000) safe = safe.substring(0, 2000) + "…[truncado]";
        log.info("[IMPRESORA SIMULADA - {}] ticket de {} caracteres (contenido enmascarado)", loc, text.length());
        log.debug("[IMPRESORA SIMULADA - {}]\n{}", loc, safe);
    }

    @Override
    public void print(byte[] payload) {
        print(payload, "OFICINA");
    }
}
