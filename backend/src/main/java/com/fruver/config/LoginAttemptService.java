package com.fruver.config;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Freno simple contra fuerza bruta en /login (sin Redis).
 * 5 fallos en 10 min => bloqueo 10 min por usuario+IP. Se audita en login.html generico.
 */
@Component
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long WINDOW_SECONDS = 600;

    record Attempt(int failures, long firstFailureEpoch, long blockedUntilEpoch) {}

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private String key(String username, String ip) {
        return (username == null ? "?" : username.toLowerCase().strip()) + "|" + (ip == null ? "?" : ip);
    }

    public boolean isBlocked(String username, String ip) {
        Attempt a = attempts.get(key(username, ip));
        return a != null && Instant.now().getEpochSecond() < a.blockedUntilEpoch();
    }

    public void loginFailed(String username, String ip) {
        String k = key(username, ip);
        long now = Instant.now().getEpochSecond();
        attempts.compute(k, (kk, prev) -> {
            if (prev == null || now - prev.firstFailureEpoch() > WINDOW_SECONDS) {
                return new Attempt(1, now, 0);
            }
            int f = prev.failures() + 1;
            long blocked = f >= MAX_ATTEMPTS ? now + WINDOW_SECONDS : prev.blockedUntilEpoch();
            return new Attempt(f, prev.firstFailureEpoch(), blocked);
        });
    }

    public void loginSucceeded(String username, String ip) {
        attempts.remove(key(username, ip));
    }

    public long blockedSecondsLeft(String username, String ip) {
        Attempt a = attempts.get(key(username, ip));
        if (a == null) return 0;
        return Math.max(0, a.blockedUntilEpoch() - Instant.now().getEpochSecond());
    }
}
