package com.fruver.infrastructure.printing;

import com.fruver.application.port.PrintStrategy;
import com.fruver.application.port.ThermalPrinterService;
import com.fruver.application.service.TicketData;
import com.fruver.application.service.TicketFormatter;
import com.fruver.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EscPosNetworkPrintStrategy implements PrintStrategy {

    private static final Logger log = LoggerFactory.getLogger(EscPosNetworkPrintStrategy.class);

    private final ThermalPrinterService printer;
    private final AppProperties props;

    public EscPosNetworkPrintStrategy(ThermalPrinterService printer, AppProperties props) {
        this.printer = printer;
        this.props = props;
    }

    @Override
    public String getStrategyName() {
        return "RED_ESCPOS";
    }

    @Override
    public void print(TicketData ticket, String location) throws Exception {
        log.info("Imprimiendo orden #{} en tiquetera térmica ESC/POS ({})", ticket.orderNumber(), location);
        byte[] payload = EscPosEncoder.encode(TicketFormatter.format(ticket, props.printing().columns()));
        printer.print(payload, location);
        log.info("✓ Orden #{} enviada exitosamente por ESC/POS a {}", ticket.orderNumber(), location);
    }
}

