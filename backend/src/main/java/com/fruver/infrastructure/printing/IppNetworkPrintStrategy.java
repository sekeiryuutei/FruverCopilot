package com.fruver.infrastructure.printing;

import com.fruver.application.port.PrintStrategy;
import com.fruver.application.service.PrinterConfigService;
import com.fruver.application.service.TicketData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class IppNetworkPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(IppNetworkPrintStrategy.class);

    private final TalonarioRasterService talonarioRaster;
    private final PrinterConfigService printerConfig;

    public IppNetworkPrintStrategy(TalonarioRasterService talonarioRaster, PrinterConfigService printerConfig) {
        this.talonarioRaster = talonarioRaster;
        this.printerConfig = printerConfig;
    }

    @Override
    public String getStrategyName() {
        return "RED_IPP";
    }

    @Override
    public void print(TicketData ticket, String location) throws Exception {
        String host = printerConfig.hostFor(location);
        int port = printerConfig.portFor(location);

        log.info("Imprimiendo orden #{} por IPP directo a {}:{} ({})", ticket.orderNumber(), host, port, location);
        byte[] raster = talonarioRaster.renderTicketToPwgRaster(ticket);
        IppClient.printRaster(host, port, "Pedido_" + ticket.orderNumber(), raster);
        log.info("✓ Orden #{} enviada exitosamente por IPP a {}:{}", ticket.orderNumber(), host, port);
    }
}

