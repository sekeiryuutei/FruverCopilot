package com.fruver.application.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class Actor {
    private Actor() {}

    /** Usuario autenticado, o "sistema" en tareas de fondo. */
    public static String name() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !a.isAuthenticated() || "anonymousUser".equals(a.getName()) ? "sistema" : a.getName();
    }
}
