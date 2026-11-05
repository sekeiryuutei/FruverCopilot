package com.fruver.application.service;

public final class Phones {
    private Phones() {}

    public static String mask(String phone) {
        return phone;
    }

    public static String normalize(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 10 && digits.startsWith("3")) {
            return "57" + digits;
        }
        return digits;
    }
}
