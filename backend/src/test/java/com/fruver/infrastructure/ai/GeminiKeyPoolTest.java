package com.fruver.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class GeminiKeyPoolTest {

    @Test
    void rotacionRoundRobinEntreMultiplesKeys() {
        GeminiKeyPool pool = new GeminiKeyPool("AIzaKeyUno123, AIzaKeyDos456, AIzaKeyTres789", null);
        assertEquals(3, pool.size());

        var k1 = pool.next();
        var k2 = pool.next();
        var k3 = pool.next();
        var k4 = pool.next();

        assertEquals(0, k1.index());
        assertEquals("AIzaKeyUno123", k1.key());

        assertEquals(1, k2.index());
        assertEquals("AIzaKeyDos456", k2.key());

        assertEquals(2, k3.index());
        assertEquals("AIzaKeyTres789", k3.key());

        // Vuelve a la primera
        assertEquals(0, k4.index());
    }

    @Test
    void keyEnCooldownSeOmiteHastaQueExpira() {
        GeminiKeyPool pool = new GeminiKeyPool("AIzaKeyUno123, AIzaKeyDos456", null);
        var k1 = pool.next();
        assertEquals(0, k1.index());

        // Poner k1 en cooldown por HTTP 429
        pool.onFailure(0, 429, Duration.ofMinutes(1));

        // Las siguientes selecciones deben usar la key 1 (index 1) porque index 0 está en cooldown
        var next1 = pool.next();
        assertEquals(1, next1.index());

        var next2 = pool.next();
        assertEquals(1, next2.index());

        // Al recuperarse (onSuccess), vuelve a estar disponible
        pool.onSuccess(0);
        var next3 = pool.next();
        assertEquals(0, next3.index());
    }

    @Test
    void toleraEspaciosYLineasVacias() {
        GeminiKeyPool pool = new GeminiKeyPool("  AIzaKeyUno123 , \n #comentario \n ,  AIzaKeyDos456  ", null);
        assertEquals(2, pool.size());
        assertEquals("AIzaKeyUno123", pool.next().key());
        assertEquals("AIzaKeyDos456", pool.next().key());
    }

    @Test
    void fallbackASingleKeySiNoHayLista() {
        GeminiKeyPool pool = new GeminiKeyPool(null, "AIzaSingleKey999");
        assertEquals(1, pool.size());
        assertEquals("AIzaSingleKey999", pool.next().key());
    }
}
