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
 * Pool de API Keys de Gemini para balancear carga en ráfagas tolerables (ej. 5 llamadas seguidas por key).
 * Al usar cuentas separadas, cada una cuenta con sus propios 15 RPM y 1.500 RPD en free tier.
 * Ejecuta 5 llamadas seguidas en Key #1, luego 5 en Key #2, luego 5 en Key #3 para mantener una carga
 * estable y predecible en cada canal sin saturar ninguno.
 * Si alguna key recibe 429 (Rate Limit), entra en enfriamiento automático (cooldown de 60s)
 * y el pool salta inmediatamente a la siguiente key disponible sin afectar la operación.
 */
public class GeminiKeyPool {

    private static final Logger log = LoggerFactory.getLogger(GeminiKeyPool.class);
    public static final int DEFAULT_BATCH_SIZE = 5;

    public record KeyEntry(String key, int index, String masked) {}

    private final List<String> keys;
    private final Instant[] cooldowns;
    private final AtomicInteger counter = new AtomicInteger(0);
    private final int batchSize;

    public GeminiKeyPool(String rawKeys, String fallbackSingleKey) {
        this(rawKeys, fallbackSingleKey, DEFAULT_BATCH_SIZE);
    }

    public GeminiKeyPool(String rawKeys, String fallbackSingleKey, int batchSize) {
        this.batchSize = Math.max(1, batchSize);
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
            log.info("GeminiKeyPool inicializado con {} API key(s) de Gemini ({} llamadas seguidas por key) para balanceo y tolerancia a 429.",
                    this.keys.size(), this.batchSize);
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

        // 1. Determinar key según ráfagas consecutivas (ej: 5 a la key 1, luego 5 a la key 2...)
        int callNum = counter.getAndIncrement();
        int baseIndex = Math.floorMod(callNum / batchSize, size);

        // Si la key base asignada no está en enfriamiento, usarla
        if (!cooldowns[baseIndex].isAfter(now)) {
            return new KeyEntry(keys.get(baseIndex), baseIndex, mask(keys.get(baseIndex)));
        }

        // Si la key base está en cooldown por un 429 previo, rotar a la siguiente sana
        for (int i = 1; i < size; i++) {
            int altIndex = Math.floorMod(baseIndex + i, size);
            if (!cooldowns[altIndex].isAfter(now)) {
                return new KeyEntry(keys.get(altIndex), altIndex, mask(keys.get(altIndex)));
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
