package com.fruver.domain;

import com.fruver.domain.enums.UnitOfMeasure;
import com.fruver.domain.service.Money;
import com.fruver.domain.service.TextTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MoneyAndTokensTest {

    @Test void formatsColombianPesosWithoutDecimals() {
        assertEquals("$15.500", Money.format(new BigDecimal("15500")));
        assertEquals("$0", Money.format(BigDecimal.ZERO));
    }
    @Test void lineSubtotalIsQuantityTimesPrice() {
        assertEquals(new BigDecimal("6750"), Money.line(new BigDecimal("1.5"), new BigDecimal("4500")));
    }
    @Test void roundingIsHalfUpToWholePesos() {
        assertEquals(new BigDecimal("1667"), Money.line(new BigDecimal("0.5"), new BigDecimal("3333")));
        assertEquals(new BigDecimal("1666"), Money.line(new BigDecimal("0.4999"), new BigDecimal("3333")));
    }
    @Test void unitParsingIsTolerantAndSafe() {
        assertEquals(UnitOfMeasure.LB, UnitOfMeasure.parse("Libras").orElseThrow());
        assertEquals(UnitOfMeasure.KG, UnitOfMeasure.parse(" kilo ").orElseThrow());
        assertEquals(UnitOfMeasure.UNIDAD, UnitOfMeasure.parse("UN").orElseThrow());
        assertTrue(UnitOfMeasure.parse("arrobas").isEmpty());
        assertTrue(UnitOfMeasure.parse(null).isEmpty());
    }
    @Test void tokensDropNoiseAndStemPlurals() {
        assertEquals(List.of("tomat", "limon"), TextTokens.significant("Dos libras de tomates y cinco limones"));
        assertEquals(List.of("papa", "criolla"), TextTokens.significant("papa criolla"));
        assertTrue(TextTokens.significant("5 mil de eso").isEmpty());
    }
}
