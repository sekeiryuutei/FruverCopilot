package com.fruver.domain.enums;

/** Tipo de precio. Hoy coincide con CustomerType, pero es un concepto aparte: se pueden agregar tipos sin tocar clientes. */
public enum PriceType {
    RETAIL, WHOLESALE;

    public static PriceType forCustomer(CustomerType type) {
        return type == CustomerType.WHOLESALE ? WHOLESALE : RETAIL;
    }
}
