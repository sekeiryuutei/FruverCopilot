package com.fruver.application.service;

import com.fruver.domain.service.Money;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Formato de ticket en columnas fijas (32 = papel 58mm, 42/48 = 80mm). Independiente del modelo de impresora. */
public final class TicketFormatter {

    public record TicketLine(String text, boolean bold) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TicketFormatter() {}

    public static List<TicketLine> format(TicketData t, int cols) {
        List<TicketLine> out = new ArrayList<>();
        String sep = "-".repeat(cols);
        if (t.reprint()) out.add(new TicketLine(center("*** REIMPRESION ***", cols), true));
        out.add(new TicketLine(center(t.shop(), cols), true));
        out.add(new TicketLine(sep, false));
        out.add(new TicketLine("PEDIDO #" + t.orderNumber(), true));
        out.add(new TicketLine("FECHA: " + t.at().format(DATE), false));
        wrap("CLIENTE: " + nz(t.customer()), cols, false, out);
        if (t.phone() != null && !t.phone().isBlank()) out.add(new TicketLine("TEL: " + t.phone(), false));
        out.add(new TicketLine("TIPO: " + ("WHOLESALE".equals(t.customerType()) ? "MAYORISTA" : "MINORISTA"), false));
        out.add(new TicketLine(sep, false));
        for (TicketData.Line l : t.lines()) {
            wrap(l.name(), cols, true, out);
            String left = "  " + Money.qty(l.quantity()) + " " + unitLabel(l.unit()) + " x " + Money.format(l.price());
            out.add(new TicketLine(leftRight(left, Money.format(l.subtotal()), cols), false));
        }
        out.add(new TicketLine(sep, false));
        out.add(new TicketLine(leftRight("TOTAL", Money.format(t.total()), cols), true));
        out.add(new TicketLine(sep, false));
        if (t.address() != null && !t.address().isBlank()) wrap("DIRECCION: " + t.address(), cols, false, out);
        if (t.notes() != null && !t.notes().isBlank()) wrap("OBS: " + t.notes(), cols, false, out);
        out.add(new TicketLine(sep, false));
        return out;
    }

    static String unitLabel(String unit) { return "UNIDAD".equals(unit) ? "UN" : unit; }

    static String center(String s, int cols) {
        if (s.length() >= cols) return s;
        return " ".repeat((cols - s.length()) / 2) + s;
    }

    static String leftRight(String left, String right, int cols) {
        int gap = cols - left.length() - right.length();
        if (gap < 1) return left + "\n" + " ".repeat(Math.max(0, cols - right.length())) + right;
        return left + " ".repeat(gap) + right;
    }

    private static void wrap(String text, int cols, boolean bold, List<TicketLine> out) {
        StringBuilder line = new StringBuilder();
        for (String word : nz(text).split("\\s+")) {
            while (word.length() > cols) { // palabra mas larga que la linea
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
