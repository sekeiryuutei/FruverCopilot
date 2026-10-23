package com.fruver.infrastructure.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool de API Keys de Gemini para balancear carga (Round-Robin) entre múltiples cuentas de Google Cloud.
 * Al usar cuentas separadas, cada una cuenta con sus propios 15 RPM y 1.500 RPD en free tier.
 * Si alguna key recibe 429 (Rate Limit), entra en enfriamiento automático (cooldown de 60s)
 * y el pool salta inmediatamente a la siguiente key disponible sin afectar la operación.
 */
public class GeminiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(GeminiKeyPool.class);

    public record KeyEntry(String key, int index, String masked) {}

    private final List<String> keys;
    private final Instant[] cooldowns;
    private final AtomicInteger counter = new AtomicInteger(0);

    public GeminiKeyPool(String rawKeys, String fallbackSingleKey) {
        List<String> list = new ArrayList<>();
        if (rawKeys != null && !rawKeys.isBlank()) {
            Arrays.stream(rawKeys.split("[,;\n]"))
                    .map(String::strip)
                    .filter(k -> !k.isBlank() && !k.startsWith("#") && k.length() > 8)
                    .forEach(list::add);
        }
        if (list.isEmpty() && fallbackSingleKey != null && !fallbackSingleKey.isBlank() && fallbackSingleKey.strip().length() > 8) {
            Arrays.stream(fallbackSingleKey.split("[,;\n]"))
                    .map(String::strip)
                    .filter(k -> !k.isBlank() && !k.startsWith("#") && k.length() > 8)
                    .forEach(list::add);
        }

        this.keys = List.copyOf(list);
        this.cooldowns = new Instant[this.keys.size()];
        Arrays.fill(this.cooldowns, Instant.EPOCH);

        if (this.keys.isEmpty()) {
            log.warn("GeminiKeyPool inicializado sin keys válidas.");
        } else {
            log.info("GeminiKeyPool inicializado con {} API key(s) de Gemini para balanceo y tolerancia a 429.", this.keys.size());
            for (int i = 0; i < this.keys.size(); i++) {
                log.info("  -> Key #{}: {}", i + 1, mask(this.keys.get(i)));
            }
        }
    }

    public synchronized KeyEntry next() {
        if (keys.isEmpty()) {
            return new KeyEntry("", 0, "(sin-key)");
        }
        Instant now = Instant.now();
        int size = keys.size();

        // 1. Intentar encontrar una key que NO esté en enfriamiento
        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(counter.getAndIncrement(), size);
            if (!cooldowns[idx].isAfter(now)) {
                return new KeyEntry(keys.get(idx), idx, mask(keys.get(idx)));
            }
        }

        // 2. Si todas están en cooldown, usar la que expire más pronto
        int bestIdx = 0;
        for (int i = 1; i < size; i++) {
            if (cooldowns[i].isBefore(cooldowns[bestIdx])) {
                bestIdx = i;
            }
        }
        long waitSec = Math.max(0, Duration.between(now, cooldowns[bestIdx]).toSeconds());
        log.warn("Todas las {} keys de Gemini están en enfriamiento. Usando la más próxima a recuperarse (Key #{}, expira en {}s)",
                size, bestIdx + 1, waitSec);
        return new KeyEntry(keys.get(bestIdx), bestIdx, mask(keys.get(bestIdx)));
    }

    public synchronized void onSuccess(int index) {
        if (index >= 0 && index < cooldowns.length) {
            cooldowns[index] = Instant.EPOCH;
        }
    }

    public synchronized void onFailure(int index, int statusCode, Duration cooldownDuration) {
        if (index >= 0 && index < cooldowns.length) {
            Instant until = Instant.now().plus(cooldownDuration);
            cooldowns[index] = until;
            log.warn("Gemini Key #{} ({}) puesta en enfriamiento por {}s debido a HTTP {}",
                    index + 1, mask(keys.get(index)), cooldownDuration.toSeconds(), statusCode);
        }
    }

    public int size() {
        return keys.size();
    }

    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    private static String mask(String key) {
        if (key == null || key.length() < 8) return "***";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
