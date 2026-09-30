package com.fruver.application.service;

import com.fruver.application.dto.Quote;
import com.fruver.application.dto.QuoteLine;
import com.fruver.domain.service.Money;

/** El mensaje de confirmacion lo arma el backend. El total NUNCA lo calcula el LLM. */
public final class ConfirmationMessageBuilder {

    private ConfirmationMessageBuilder() {}

    /** Devuelve null si la cotizacion no esta completa (no se confirma lo que no esta resuelto). */
    public static String build(Quote quote) {
        if (quote == null || !quote.complete()) return null;
        StringBuilder sb = new StringBuilder("Perfecto 😊 Te enviaremos:\n");
        for (QuoteLine l : quote.lines()) {
            sb.append("• ").append(Money.qty(l.quantity())).append(' ').append(unitWord(l.unit(), l.quantity()))
              .append(" de ").append(l.productName()).append('\n');
        }
        sb.append("\nTotal estimado: ").append(Money.format(quote.total())).append("\nEstamos preparando tu pedido.");
        return sb.toString();
    }

    private static String unitWord(String unit, java.math.BigDecimal q) {
        boolean one = q.compareTo(java.math.BigDecimal.ONE) == 0;
        return switch (unit) {
            case "LB" -> one ? "libra" : "libras";
            case "KG" -> one ? "kilo" : "kilos";
            default -> one ? "unidad" : "unidades";
        };
    }
}
