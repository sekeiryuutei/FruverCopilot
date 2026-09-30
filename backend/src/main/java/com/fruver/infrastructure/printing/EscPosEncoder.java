package com.fruver.infrastructure.printing;

import com.fruver.application.service.TicketFormatter.TicketLine;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

/**
 * Codifica un ticket a comandos ESC/POS basicos (init, negrilla, avance, corte parcial).
 * El texto se pasa a ASCII para no depender de la tabla de codigos de cada impresora.
 */
public final class EscPosEncoder {

    private EscPosEncoder() {}

    public static byte[] encode(List<TicketLine> lines) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x1B, 0x40});                 // ESC @ : inicializar
        for (TicketLine l : lines) {
            if (l.bold()) out.writeBytes(new byte[]{0x1B, 0x45, 0x01}); // ESC E 1
            out.writeBytes(ascii(l.text()).getBytes(StandardCharsets.US_ASCII));
            out.write(0x0A);
            if (l.bold()) out.writeBytes(new byte[]{0x1B, 0x45, 0x00}); // ESC E 0
        }
        out.writeBytes(new byte[]{0x1B, 0x64, 0x04});           // ESC d 4 : avanzar 4 lineas
        out.writeBytes(new byte[]{0x1D, 0x56, 0x42, 0x03});     // GS V 66 3 : corte parcial
        return out.toByteArray();
    }

    static String ascii(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder sb = new StringBuilder(n.length());
        for (char c : n.toCharArray()) sb.append(c == '\n' || (c >= 32 && c < 127) ? c : '?');
        return sb.toString();
    }
}
