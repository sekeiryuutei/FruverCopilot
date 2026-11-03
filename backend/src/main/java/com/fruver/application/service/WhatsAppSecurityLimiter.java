package com.fruver.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escudo de seguridad y limitador de tasa para prevenir ataques de denegacion de servicio
 * y consumo malicioso de tokens de IA (Denial of Wallet) via WhatsApp.
 */
@Service
public class WhatsAppSecurityLimiter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSecurityLimiter.class);

    // Limites de proteccion ampliados (no limitar pedidos de clientes con multiples audios/mensajes)
    private static final int MAX_REQUESTS_5_MIN = 35;     // Hasta 35 mensajes/audios en 5 minutos por numero
    private static final int MAX_REQUESTS_1_HOUR = 100;   // Hasta 100 mensajes en 1 hora por numero
    private static final int MAX_PAYLOAD_CHARS = 5000;    // Maximo 5.000 caracteres por mensaje
    private static final long NOTIFY_COOLDOWN_SEC = 900;  // 15 minutos entre avisos automaticos al cliente

    public record SecurityCheckResult(boolean allowed, String reason) {
        public static SecurityCheckResult allow() {
            return new SecurityCheckResult(true, null);
        }
        public static SecurityCheckResult block(String reason) {
            return new SecurityCheckResult(false, reason);
        }
    }

    private static class ClientHistory {
        final Deque<Long> requestTimestamps = new ArrayDeque<>();
        int lastTextHash = 0;
        long lastTextTime = 0;
        int duplicateCount = 0;
        long lastNotifiedTime = 0;
    }

    private final Map<String, ClientHistory> clientHistories = new ConcurrentHashMap<>();

    /**
     * Evalua si una peticion entrante debe ser procesada por la IA o bloqueada preventivamente.
     */
    public synchronized SecurityCheckResult evaluate(String phone, String text) {
        if (phone == null || phone.isBlank()) {
            return SecurityCheckResult.allow();
        }

        long now = Instant.now().getEpochSecond();
        String cleanPhone = phone.replaceAll("[^0-9]", "");
        ClientHistory history = clientHistories.computeIfAbsent(cleanPhone, k -> new ClientHistory());

        // 1. Limpieza de historial antiguo (> 1 hora)
        while (!history.requestTimestamps.isEmpty() && (now - history.requestTimestamps.peekFirst()) > 3600) {
            history.requestTimestamps.pollFirst();
        }

        // 2. Validacion de tamano anormal de payload
        if (text != null && text.length() > MAX_PAYLOAD_CHARS) {
            log.warn("[ESCUDO IA] Payload anormalmente largo de {} ({} chars)", cleanPhone, text.length());
            return SecurityCheckResult.block("Texto con longitud sospechosamente anormal (> " + MAX_PAYLOAD_CHARS + " caracteres)");
        }

        // 3. Deteccion de mensajes duplicados en rafaga instantanea (tolerante con reintentos de webhook de WhatsApp)
        if (text != null && !text.isBlank()) {
            int currentHash = text.trim().toLowerCase().hashCode();
            if (currentHash == history.lastTextHash && (now - history.lastTextTime) < 15) {
                history.duplicateCount++;
                if (history.duplicateCount >= 4) {
                    log.warn("[ESCUDO IA] Bombardeo de mensajes idénticos detectado para {} ({} veces)", cleanPhone, history.duplicateCount);
                    return SecurityCheckResult.block("Mensaje idéntico recibido en ráfaga excesiva");
                }
            } else {
                history.duplicateCount = 0;
            }
            history.lastTextHash = currentHash;
            history.lastTextTime = now;
        }

        // 4. Conteo en ventana corta (5 minutos)
        long count5Min = history.requestTimestamps.stream().filter(ts -> (now - ts) <= 300).count();
        if (count5Min >= MAX_REQUESTS_5_MIN) {
            log.warn("[ESCUDO IA] Rafaga detectada para {}: {} peticiones en 5 min", cleanPhone, count5Min + 1);
            return SecurityCheckResult.block("Ráfaga excesiva de mensajes en poco tiempo (> " + MAX_REQUESTS_5_MIN + " en 5 min)");
        }

        // 5. Conteo en ventana horaria (1 hora)
        if (history.requestTimestamps.size() >= MAX_REQUESTS_1_HOUR) {
            log.warn("[ESCUDO IA] Limite horario superado para {}: {} peticiones en 1 hora", cleanPhone, history.requestTimestamps.size() + 1);
            return SecurityCheckResult.block("Límite horario de peticiones excedido (> " + MAX_REQUESTS_1_HOUR + "/hora)");
        }

        // Registrar timestamp de peticion autorizada
        history.requestTimestamps.addLast(now);
        return SecurityCheckResult.allow();
    }

    /**
     * Verifica si se debe enviar mensaje de cortesia o enfriar para no sobrecargar de mensajes al remitente.
     */
    public synchronized boolean shouldNotifyClient(String phone) {
        if (phone == null || phone.isBlank()) return false;
        long now = Instant.now().getEpochSecond();
        String cleanPhone = phone.replaceAll("[^0-9]", "");
        ClientHistory history = clientHistories.computeIfAbsent(cleanPhone, k -> new ClientHistory());
        if (now - history.lastNotifiedTime > NOTIFY_COOLDOWN_SEC) {
            history.lastNotifiedTime = now;
            return true;
        }
        return false;
    }
}

