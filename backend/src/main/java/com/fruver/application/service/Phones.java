package com.fruver.application.service;

public final class Phones {
    private Phones() {}

    public static String mask(String phone) {
        if (phone == null || phone.length() <= 4) return phone;
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
