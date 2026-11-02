package com.fruver.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filtro de defensa perimetral contra saturacion HTTP (DDoS / Brute-force / Scraping malicioso).
 * Aplica ventana deslizante de peticiones por direccion IP.
 */
public class HttpRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpRateLimitFilter.class);

    private static final int MAX_REQUESTS_PER_MINUTE = 150;
    private static final long WINDOW_SECONDS = 60;

    private final Map<String, Deque<Long>> ipRequests = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String clientIp = resolveClientIp(request);

        // Si es red interna (Docker/localhost), no limitar
        if (isInternalIp(clientIp)) {
            filterChain.doFilter(request, response);
            return;
        }

        long now = Instant.now().getEpochSecond();
        Deque<Long> timestamps = ipRequests.computeIfAbsent(clientIp, k -> new ArrayDeque<>());

        boolean blocked = false;
        synchronized (timestamps) {
            // Limpiar peticiones fuera de la ventana
            while (!timestamps.isEmpty() && (now - timestamps.peekFirst()) > WINDOW_SECONDS) {
                timestamps.pollFirst();
            }

            if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
                blocked = true;
            } else {
                timestamps.addLast(now);
            }
        }

        if (blocked) {
            log.warn("[FIREWALL HTTP] IP {} bloqueada temporalmente por exceso de peticiones ({}/min)", clientIp, MAX_REQUESTS_PER_MINUTE);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"Demasiadas peticiones. Por favor espere un momento antes de reintentar.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest req) {
        String xForwarded = req.getHeader("X-Forwarded-For");
        if (xForwarded != null && !xForwarded.isBlank()) {
            return xForwarded.split(",")[0].trim();
        }
        String xReal = req.getHeader("X-Real-IP");
        if (xReal != null && !xReal.isBlank()) {
            return xReal.trim();
        }
        return req.getRemoteAddr();
    }

    private boolean isInternalIp(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1")) return true;
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        // Docker subnets 172.16.0.0 - 172.31.255.255
        if (ip.startsWith("172.")) {
            try {
                String[] parts = ip.split("\\.");
                if (parts.length >= 2) {
                    int second = Integer.parseInt(parts[1]);
                    return second >= 16 && second <= 31;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }
}

