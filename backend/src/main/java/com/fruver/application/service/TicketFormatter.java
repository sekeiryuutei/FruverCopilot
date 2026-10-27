package com.fruver.application.service;

import com.fruver.domain.service.Money;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Formato de ticket y comanda mayorista en columnas fijas (32 = papel 58mm, 42/48 = 80mm).
 * Diseñado según el talonario físico de Fruver El Oasis.
 * SIN precios ni totales: el personal los calcula manualmente.
 */
public final class TicketFormatter {

    public record TicketLine(String text, boolean bold) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private TicketFormatter() {}

    public static List<TicketLine> format(TicketData t, int cols) {
        List<TicketLine> out = new ArrayList<>();
        String sep = "-".repeat(cols);
        String doubleSep = "=".repeat(cols);

        // Reimpresión
        if (t.reprint()) out.add(new TicketLine(center("*** REIMPRESION ***", cols), true));

        // Encabezado: nombre del negocio bien grande
        out.add(new TicketLine(center("FRUVER EL OASIS", cols), true));
        out.add(new TicketLine(center("Frutas y Verduras al por Mayor", cols), false));
        out.add(new TicketLine(sep, false));

        // Número de factura grande y prominente
        if (cols >= 40) {
            out.add(new TicketLine(center("*** FACTURA #" + t.orderNumber() + " ***", cols), true));
            out.add(new TicketLine("FECHA: " + t.at().format(DATE), false));
        } else {
            out.add(new TicketLine(center("** FACTURA #" + t.orderNumber() + " **", cols), true));
            out.add(new TicketLine("FECHA: " + t.at().format(DATE), false));
        }

        out.add(new TicketLine(sep, false));

        // Datos del cliente
        wrap("CLIENTE: " + nz(t.customer()), cols, true, out);
        if ("WHOLESALE".equals(t.customerType())) out.add(new TicketLine("TIPO: MAYORISTA", false));
        if (t.phone() != null && !t.phone().isBlank()) {
            String p = t.phone().strip();
            if (p.startsWith("57") && p.length() == 12) p = p.substring(2);
            out.add(new TicketLine("TEL: " + p, false));
        }
        if (t.address() != null && !t.address().isBlank()) wrap("DIRECCION: " + t.address(), cols, false, out);
        if (t.deliveryPersonName() != null && !t.deliveryPersonName().isBlank()) wrap("REPARTIDOR: " + t.deliveryPersonName(), cols, true, out);
        if (t.notes() != null && !t.notes().isBlank()) wrap("OBS: " + t.notes(), cols, false, out);

        out.add(new TicketLine(doubleSep, false));
        // Cabecera de la tabla: PRODUCTO / CANTIDAD y espacio para PRECIO
        String colHeader = cols >= 40
                ? leftRight("DESCRIPCION / CANT.", "VALOR", cols)
                : leftRight("PRODUCTO / CANT.", "VALOR", cols);
        out.add(new TicketLine(colHeader, true));
        out.add(new TicketLine(doubleSep, false));

        for (TicketData.Line l : t.lines()) {
            String qtyStr = l.quantity() != null ? Money.qty(l.quantity()) : "[   ]";
            String qtyAndUnit = qtyStr + " " + unitLabel(l.unit());
            String priceStr = (l.subtotal() != null && l.subtotal().compareTo(BigDecimal.ZERO) > 0)
                    ? Money.format(l.subtotal())
                    : "$ [        ]";
            formatItemLine(l.name(), qtyAndUnit, priceStr, cols, out);
        }

        out.add(new TicketLine(doubleSep, false));
        // Espacio manual para que el personal anote el total (o total calculado si existe)
        String totalValue = (t.total() != null && t.total().compareTo(BigDecimal.ZERO) > 0)
                ? Money.format(t.total())
                : (cols >= 40 ? "[                    ]" : "[              ]");
        out.add(new TicketLine(leftRight("TOTAL $:", totalValue, cols), true));
        out.add(new TicketLine(sep, false));
        // Firma del receptor
        String nitFill = "_".repeat(Math.max(4, cols - 9));
        out.add(new TicketLine("RECIBI: " + nitFill, false));
        out.add(new TicketLine(sep, false));
        return out;
    }

    static void formatItemLine(String name, String qtyAndUnit, String priceStr, int cols, List<TicketLine> out) {
        String cleanName = (name != null ? name.strip() : "Artículo");
        String fullLeft = cleanName + " " + qtyAndUnit;
        int maxLeft = cols - priceStr.length() - 1;

        if (fullLeft.length() <= maxLeft) {
            out.add(new TicketLine(leftRight(fullLeft, priceStr, cols), false));
            return;
        }

        // Si el nombre completo con cantidad excede el ancho para una sola línea:
        // Envolvemos el exceso del nombre arriba, y en la última línea dejamos el final del nombre + cantidad + precio.
        String[] words = cleanName.split("\\s+");
        StringBuilder top = new StringBuilder();
        StringBuilder bottom = new StringBuilder();

        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            String remainingWithQty = String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)) + " " + qtyAndUnit;
            if (remainingWithQty.length() <= maxLeft) {
                bottom.append(remainingWithQty);
                break;
            }
            if (top.length() + w.length() + (top.length() > 0 ? 1 : 0) <= cols) {
                if (top.length() > 0) top.append(" ");
                top.append(w);
            } else {
                if (top.length() > 0) {
                    out.add(new TicketLine(top.toString(), false));
                    top.setLength(0);
                }
                top.append(w);
            }
        }

        if (top.length() > 0) {
            out.add(new TicketLine(top.toString(), false));
        }

        String lastLeft = bottom.length() > 0 ? bottom.toString() : qtyAndUnit;
        out.add(new TicketLine(leftRight(lastLeft, priceStr, cols), false));
    }

    static String unitLabel(String unit) {
        if (unit == null || unit.isBlank() || "null".equalsIgnoreCase(unit)) return "[    ]";
        return switch (unit.toUpperCase()) {
            case "UNIDAD" -> "UN";
            case "BULTO"  -> "BLT";
            case "ARROBA" -> "ARR";
            case "CAJA"   -> "CJA";
            case "ATADO"  -> "ATD";
            case "BOLSA"  -> "BLS";
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
