package com.fruver.infrastructure.printing;

import com.fruver.application.port.PrintStrategy;
import com.fruver.application.service.PrinterConfigService;
import org.springframework.stereotype.Component;

@Component
public class PrintStrategyFactory {

    private final BrotherEmailPrintStrategy emailStrategy;
    private final IppNetworkPrintStrategy ippStrategy;
    private final EscPosNetworkPrintStrategy escPosStrategy;
    private final RpiSshPrintStrategy rpiSshStrategy;
    private final PrinterConfigService printerConfig;

    public PrintStrategyFactory(BrotherEmailPrintStrategy emailStrategy,
                                IppNetworkPrintStrategy ippStrategy,
                                EscPosNetworkPrintStrategy escPosStrategy,
                                RpiSshPrintStrategy rpiSshStrategy,
                                PrinterConfigService printerConfig) {
        this.emailStrategy = emailStrategy;
        this.ippStrategy = ippStrategy;
        this.escPosStrategy = escPosStrategy;
        this.rpiSshStrategy = rpiSshStrategy;
        this.printerConfig = printerConfig;
    }

    public PrintStrategy getStrategy(String location) {
        PrinterConfigService.PrinterConfig cfg = printerConfig.get();
        String type;

        if ("OFICINA".equalsIgnoreCase(location) || "FRUVER".equalsIgnoreCase(location)) {
            type = cfg.officePrintType();
        } else {
            type = cfg.homePrintType();
        }

        if (type == null || type.isBlank()) {
            type = "EMAIL";
        }

        int port = printerConfig.portFor(location);
        String host = printerConfig.hostFor(location);

        // Si el entorno tiene host 127.0.0.1 y puerto 9100 (pruebas automáticas con SimulatedPrinter), usar ESCPOS
        if (port == 9100 && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
            return escPosStrategy;
        }

        String normalized = type.trim().toUpperCase();
        if (normalized.contains("RPI") || normalized.contains("RASPBERRY") || normalized.contains("SSH")) {
            return rpiSshStrategy;
        } else if (normalized.contains("EMAIL") || normalized.contains("CORREO")) {
            return emailStrategy;
        } else if (normalized.contains("ESCPOS") || normalized.contains("TERMICA")) {
            return escPosStrategy;
        } else if (normalized.contains("IPP")) {
            return ippStrategy;
        } else if (port == 9100) {
            return escPosStrategy;
        } else if (port == 631) {
            return ippStrategy;
        }

        return emailStrategy;
    }
}
