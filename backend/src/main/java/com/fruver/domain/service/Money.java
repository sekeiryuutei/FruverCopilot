package com.fruver.domain.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Moneda COP: escala 0, redondeo HALF_UP, formato $15.500. */
public final class Money {
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    public static final String CURRENCY = "COP";

    private Money() {}

    public static BigDecimal line(BigDecimal quantity, BigDecimal unitPrice) {
        return quantity.multiply(unitPrice).setScale(0, ROUNDING);
    }

    public static String format(BigDecimal value) {
        DecimalFormatSymbols s = new DecimalFormatSymbols(Locale.ROOT);
        s.setGroupingSeparator('.');
        return "$" + new DecimalFormat("#,##0", s).format(value.setScale(0, ROUNDING));
    }

    public static String qty(BigDecimal q) {
        return q.stripTrailingZeros().toPlainString();
    }
}
