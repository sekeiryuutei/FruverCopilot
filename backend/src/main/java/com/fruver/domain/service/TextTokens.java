package com.fruver.domain.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Tokenizacion simple en espanol para buscar candidatos de catalogo. */
public final class TextTokens {

    private static final Set<String> STOP = Set.of(
            "del", "las", "los", "una", "uno", "unos", "unas", "con", "sin", "por", "para", "que", "mas", "mil",
            "mande", "mandeme", "deme", "dame", "favor", "buenas", "buenos", "dias", "tardes", "noches", "hola",
            "libra", "libras", "kilo", "kilos", "unidad", "unidades", "grande", "grandes", "mediana", "medianas",
            "pequena", "pequenas", "bien", "tiene", "sino", "dos", "tres", "cuatro", "cinco", "seis", "siete",
            "ocho", "nueve", "diez", "media", "medio", "poquito", "esas", "esos", "ese", "esa", "eso", "esto", "ayer", "mismo",
            "gracias", "vecino", "vecina", "quiero", "necesito", "pedido", "otro", "otra");

    private TextTokens() {}

    public static String fold(String s) {
        return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public static List<String> significant(String text) {
        List<String> out = new ArrayList<>();
        for (String t : fold(text).split("[^a-z]+")) {
            if (t.length() < 3 || STOP.contains(t)) continue;
            String stem = stem(t);
            if (!out.contains(stem)) out.add(stem);
            if (out.size() >= 8) break;
        }
        return out;
    }

    static String stem(String t) {
        if (t.length() > 5 && t.endsWith("es")) return t.substring(0, t.length() - 2);
        if (t.length() > 3 && t.endsWith("s")) return t.substring(0, t.length() - 1);
        return t;
    }
}
