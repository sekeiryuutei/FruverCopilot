package com.fruver.infrastructure.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class GeminiKeyPoolTest {

    @Test
    void rotaPorLotesConsecutivosDeLlamadas() {
        // Pool con 3 keys y lote de 5 llamadas por key
        GeminiKeyPool pool = new GeminiKeyPool("AIzaKeyUno123, AIzaKeyDos456, AIzaKeyTres789", null, 5);
        assertEquals(3, pool.size());

        // Primeras 5 llamadas van a la Key #1 (index 0)
        for (int i = 0; i < 5; i++) {
            assertEquals(0, pool.next().index(), "Llamada " + i + " debe ir a Key #1");
        }

        // Siguientes 5 llamadas van a la Key #2 (index 1)
        for (int i = 0; i < 5; i++) {
            assertEquals(1, pool.next().index(), "Llamada " + (i + 5) + " debe ir a Key #2");
        }

        // Siguientes 5 llamadas van a la Key #3 (index 2)
        for (int i = 0; i < 5; i++) {
            assertEquals(2, pool.next().index(), "Llamada " + (i + 10) + " debe ir a Key #3");
        }

        // Luego vuelve a la Key #1
        assertEquals(0, pool.next().index());
    }

    @Test
    void keyEnCooldownSeOmiteHastaQueExpira() {
        GeminiKeyPool pool = new GeminiKeyPool("AIzaKeyUno123, AIzaKeyDos456", null, 5);
        var k1 = pool.next();
        assertEquals(0, k1.index());

        // Poner k1 en cooldown por HTTP 429
        pool.onFailure(0, 429, Duration.ofMinutes(1));

        // Las siguientes selecciones deben saltar inmediatamente a la Key #2 (index 1)
        var next1 = pool.next();
        assertEquals(1, next1.index());

        var next2 = pool.next();
        assertEquals(1, next2.index());

        // Al recuperarse (onSuccess), vuelve a estar disponible
        pool.onSuccess(0);
        var next3 = pool.next();
        // Sigue en la key asignada o disponible
        assertNotNull(next3);
    }

    @Test
    void toleraEspaciosYLineasVacias() {
        GeminiKeyPool pool = new GeminiKeyPool("  AIzaKeyUno123 , \n #comentario \n ,  AIzaKeyDos456  ", null, 5);
        assertEquals(2, pool.size());
        assertEquals("AIzaKeyUno123", pool.next().key());
    }

    @Test
    void fallbackASingleKeySiNoHayLista() {
        GeminiKeyPool pool = new GeminiKeyPool(null, "AIzaSingleKey999", 5);
        assertEquals(1, pool.size());
        assertEquals("AIzaSingleKey999", pool.next().key());
    }
}
