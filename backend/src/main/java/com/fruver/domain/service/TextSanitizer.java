package com.fruver.domain.service;

import java.util.regex.Pattern;

/**
 * Servicio utilitario para normalizar y blindar textos contra emojis,
 * caracteres de control, caracteres suplementarios no soportados por impresoras térmicas/láser,
 * meta-razonamientos de IA y longitudes excesivas que puedan desbordar formularios o talonarios.
 */
public final class TextSanitizer {

    private static final Pattern MULTI_WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PARENTHESIZED_AI_NOTES = Pattern.compile(
            "(?i)\\s*\\([^)]*?\\b(tomado\\s+de|del\\s+pedido\\s+actual|mencionado\\s+en|historial\\s+frecuente|interpretado\\s+como|producto\\s+adicional|observaci[oó]n|nota)\\b[^)]*?\\)"
    );
    private static final Pattern AI_OBSERVATION_PATTERNS = Pattern.compile(
            "(?i)\\b(tomado\\s+de|del\\s+pedido\\s+actual|mencionado\\s+en|historial\\s+frecuente|interpretado\\s+como|producto\\s+adicional|observaci[oó]n:?|nota:?)\\b.*"
    );

    private TextSanitizer() {
        // Utility class
    }

    /**
     * Limpia un texto eliminando cualquier emoji (incluyendo secuencias complejas ZWJ,
     * modificadores de tono de piel y pictogramas suplementarios), caracteres de control
     * y caracteres fuera del BMP estándar de impresión, conservando acentos en español,
     * eñes, números y signos de puntuación comercial.
     */
    public static String cleanText(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int charCount = Character.charCount(cp);

            boolean isAllowed = cp <= 0xFFFF
                    && !Character.isISOControl(cp)
                    && cp != 0xFE0F && cp != 0x200D
                    && Character.getType(cp) != Character.SURROGATE
                    && Character.getType(cp) != Character.OTHER_SYMBOL
                    && Character.getType(cp) != Character.MODIFIER_SYMBOL
                    && !(cp >= 0x2600 && cp <= 0x27BF); // Dingbats y símbolos misceláneos

            if (isAllowed) {
                sb.appendCodePoint(cp);
            }
            i += charCount;
        }

        return MULTI_WHITESPACE.matcher(sb.toString()).replaceAll(" ").trim();
    }

    /**
     * Escapa caracteres sensibles para renderizado seguro en HTML.
     */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * Sanitiza y trunca de forma segura a una longitud máxima dada sin cortar abruptamente
     * si es posible.
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength).trim();
    }

    /**
     * Sanitiza el nombre del cliente: elimina emojis, espacios dobles y trunca a máximo 40 caracteres.
     */
    public static String sanitizeCustomerName(String name) {
        String clean = cleanText(name);
        if (clean.isBlank()) {
            return "CLIENTE GENERAL";
        }
        return truncate(clean, 40);
    }

    /**
     * Sanitiza la dirección de entrega: elimina emojis, saltos raros y trunca a máximo 60 caracteres.
     */
    public static String sanitizeAddress(String address) {
        String clean = cleanText(address);
        if (clean.isBlank()) {
            return "";
        }
        return truncate(clean, 60);
    }

    /**
     * Sanitiza notas y observaciones generales: elimina emojis, limpia meta-razonamientos y trunca a 100 caracteres.
     */
    public static String sanitizeNotes(String notes) {
        String clean = cleanText(notes);
        if (clean.isBlank()) {
            return "";
        }
        clean = cleanAiExplanations(clean);
        return truncate(clean, 100);
    }

    /**
     * Sanitiza el nombre del producto para la columna del talonario:
     * elimina emojis, remueve notas de IA y trunca a 45 caracteres.
     */
    public static String sanitizeProductName(String productName) {
        String clean = cleanText(productName);
        if (clean.isBlank()) {
            return "";
        }
        clean = cleanAiExplanations(clean);
        return truncate(clean, 45);
    }

    /**
     * Remueve frases típicas de razonamiento o meta-comentarios generados por la IA en nombres o notas.
     */
    public static String cleanAiExplanations(String text) {
        if (text == null || text.isBlank()) return "";
        String cleaned = PARENTHESIZED_AI_NOTES.matcher(text).replaceAll("");
        cleaned = AI_OBSERVATION_PATTERNS.matcher(cleaned).replaceAll("").trim();
        // Si quedó con paréntesis vacíos o residuales "()" o "(", los removemos
        cleaned = cleaned.replaceAll("\\(\\s*\\)", "").replaceAll("\\(\\s*$", "").trim();
        return MULTI_WHITESPACE.matcher(cleaned).replaceAll(" ").trim();
    }
}
