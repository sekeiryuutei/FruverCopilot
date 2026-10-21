package com.fruver.application.service;

import com.fruver.domain.service.Money;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Formato de ticket y comanda mayorista en columnas fijas (32 = papel 58mm, 42/48 = 80mm).
 * Diseñado según el talonario físico de Fruver El Oasis con espacios en blanco para precios manuales.
 */
public final class TicketFormatter {

    public record TicketLine(String text, boolean bold) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TicketFormatter() {}

    public static List<TicketLine> format(TicketData t, int cols) {
        List<TicketLine> out = new ArrayList<>();
        String sep = "-".repeat(cols);
        String doubleSep = "=".repeat(cols);

        if (t.reprint()) out.add(new TicketLine(center("*** REIMPRESION ***", cols), true));
        if (t.requiresVerification()) {
            out.add(new TicketLine(center("*** ATENCION: REVISAR LINEAS DUDOSAS ***", cols), true));
            if (t.verificationReason() != null && !t.verificationReason().isBlank()) {
                wrap("NOTA: " + t.verificationReason(), cols, true, out);
            }
            out.add(new TicketLine(sep, false));
        }

        out.add(new TicketLine(center(t.shop(), cols), true));
        out.add(new TicketLine(center("Frutas y Verduras al por Mayor", cols), false));
        out.add(new TicketLine(sep, false));

        if (cols >= 40) {
            out.add(new TicketLine("[X] PEDIDO         [ ] COTIZACION", false));
            out.add(new TicketLine("[ ] CUENTA COBRO   [ ] REMISION", false));
            out.add(new TicketLine(sep, false));
            out.add(new TicketLine("PEDIDO #" + t.orderNumber() + "   FECHA: " + t.at().format(DATE), true));
        } else {
            out.add(new TicketLine("[X] PEDIDO     [ ] COTIZACION", false));
            out.add(new TicketLine("[ ] CUENTA     [ ] REMISION", false));
            out.add(new TicketLine(sep, false));
            out.add(new TicketLine("PEDIDO #" + t.orderNumber(), true));
            out.add(new TicketLine("FECHA: " + t.at().format(DATE), false));
        }

        wrap("CLIENTE: " + nz(t.customer()), cols, true, out);
        if ("WHOLESALE".equals(t.customerType())) out.add(new TicketLine("TIPO: MAYORISTA", false));
        String nitFill = "_".repeat(Math.max(4, cols - 9));
        out.add(new TicketLine("NIT/C.C: " + nitFill, false));
        if (t.phone() != null && !t.phone().isBlank()) out.add(new TicketLine("TEL: " + t.phone(), false));
        if (t.address() != null && !t.address().isBlank()) wrap("DIRECCION: " + t.address(), cols, false, out);
        if (t.deliveryPersonName() != null && !t.deliveryPersonName().isBlank()) wrap("REPARTIDOR: " + t.deliveryPersonName(), cols, true, out);
        if (t.notes() != null && !t.notes().isBlank()) wrap("OBS: " + t.notes(), cols, false, out);

        out.add(new TicketLine(doubleSep, false));
        String colHeader = cols >= 40
                ? leftRight("CANT.  DESCRIPCION", "VR.UNIT  VR.TOTAL", cols)
                : leftRight("CANT. DESCRIPCION", "VR.U  VR.TOT", cols);
        out.add(new TicketLine(colHeader, true));
        out.add(new TicketLine(doubleSep, false));

        String blankSlots = cols >= 40 ? "[      ]  [       ]" : "[   ] [    ]";
        for (TicketData.Line l : t.lines()) {
            wrap(l.name(), cols, true, out);
            boolean hasPrice = l.price() != null && l.price().compareTo(BigDecimal.ZERO) > 0
                    && l.subtotal() != null && l.subtotal().compareTo(BigDecimal.ZERO) > 0;

            String left = "  " + Money.qty(l.quantity()) + " " + unitLabel(l.unit());
            if (hasPrice) {
                left += " x " + Money.format(l.price());
                out.add(new TicketLine(leftRight(left, Money.format(l.subtotal()), cols), false));
            } else {
                out.add(new TicketLine(leftRight(left, blankSlots, cols), false));
            }
        }

        out.add(new TicketLine(doubleSep, false));
        boolean hasTotal = t.total() != null && t.total().compareTo(BigDecimal.ZERO) > 0;
        if (hasTotal) {
            out.add(new TicketLine(leftRight("TOTAL", Money.format(t.total()), cols), true));
        } else {
            String blankTotal = cols >= 40 ? "[                 ]" : "[           ]";
            out.add(new TicketLine(leftRight("TOTAL $:", blankTotal, cols), true));
        }
        out.add(new TicketLine(sep, false));
        out.add(new TicketLine("RECIBI:  " + nitFill, false));
        out.add(new TicketLine("NIT/CC:  " + nitFill, false));
        out.add(new TicketLine(sep, false));
        return out;
    }

    static String unitLabel(String unit) {
        if (unit == null) return "UN";
        return switch (unit.toUpperCase()) {
            case "UNIDAD" -> "UN";
            case "BULTO" -> "BLT";
            case "ARROBA" -> "ARR";
            case "CAJA" -> "CJA";
            case "ATADO" -> "ATD";
            case "BOLSA" -> "BLS";
            default -> unit;
        };
    }

    static String center(String s, int cols) {
        if (s == null) return "";
        if (s.length() >= cols) return s;
        return " ".repeat((cols - s.length()) / 2) + s;
    }

    static String leftRight(String left, String right, int cols) {
        if (left == null) left = "";
        if (right == null) right = "";
        if (left.length() + right.length() >= cols) {
            int maxLeft = Math.max(0, cols - right.length() - 1);
            left = left.substring(0, Math.min(left.length(), maxLeft));
        }
        int gap = Math.max(1, cols - left.length() - right.length());
        return left + " ".repeat(gap) + right;
    }

    private static void wrap(String text, int cols, boolean bold, List<TicketLine> out) {
        StringBuilder line = new StringBuilder();
        for (String word : nz(text).split("\\s+")) {
            while (word.length() > cols) {
                if (line.length() > 0) { out.add(new TicketLine(line.toString(), bold)); line.setLength(0); }
                out.add(new TicketLine(word.substring(0, cols), bold));
                word = word.substring(cols);
            }
            if (line.length() + word.length() + (line.length() > 0 ? 1 : 0) > cols) {
                out.add(new TicketLine(line.toString(), bold));
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(new TicketLine(line.toString(), bold));
    }

    private static String nz(String s) { return s == null ? "" : s; }
}

