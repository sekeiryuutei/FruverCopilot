package com.fruver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** La app se niega a arrancar en produccion con configuraciones de desarrollo peligrosas. */
@Component
public class ProductionGuard {

    public ProductionGuard(Environment env, AppProperties props,
                           @Value("${spring.ai.openai.chat.options.model:}") String aiModel,
                           @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure,
                           @Value("${app.security.admin-password:}") String adminPassword,
                           @Value("${spring.datasource.password:}") String dbPassword) {
        if (!env.acceptsProfiles(Profiles.of("prod"))) return;
        if (props.devEndpoints()) {
            throw new IllegalStateException("DEV_ENDPOINTS=true no esta permitido con SPRING_PROFILES_ACTIVE=prod");
        }
        if (props.demoSeed().enabled()) {
            throw new IllegalStateException("SEED_DEMO_DATA=true no esta permitido con SPRING_PROFILES_ACTIVE=prod");
        }
        if ("mock".equalsIgnoreCase(aiModel)) {
            throw new IllegalStateException("AI_MODEL=mock no esta permitido con SPRING_PROFILES_ACTIVE=prod");
        }
        if (!cookieSecure) {
            throw new IllegalStateException("COOKIE_SECURE=true es obligatorio en prod (HTTPS). Activa HTTPS en el proxy.");
        }
        assertStrongSecret(adminPassword, "ADMIN_PASSWORD");
        assertStrongSecret(dbPassword, "DB_PASSWORD");
        if (props.security() != null && props.security().operatorPassword() != null
                && !props.security().operatorPassword().isBlank()
                && props.security().operatorPassword().equals(adminPassword)) {
            throw new IllegalStateException("OPERATOR_PASSWORD no puede ser igual a ADMIN_PASSWORD en prod");
        }
        if (props.whatsapp() != null && props.whatsapp().enabled()) {
            if (props.whatsapp().appSecret() == null || props.whatsapp().appSecret().isBlank()
                    || props.whatsapp().appSecret().length() < 16
                    || "ignore".equalsIgnoreCase(props.whatsapp().appSecret())
                    || "bypass".equalsIgnoreCase(props.whatsapp().appSecret())
                    || "none".equalsIgnoreCase(props.whatsapp().appSecret())) {
                throw new IllegalStateException("WHATSAPP_APP_SECRET obligatorio y valido en prod si WHATSAPP_ENABLED=true");
            }
            if (props.whatsapp().verifyToken() == null || props.whatsapp().verifyToken().isBlank()
                    || props.whatsapp().verifyToken().contains("change_me")) {
                throw new IllegalStateException("WHATSAPP_VERIFY_TOKEN debe cambiarse en prod");
            }
        }
    }

    static void assertStrongSecret(String v, String name) {
        if (v == null || v.isBlank() || "change_me".equals(v) || v.length() < 12
                || "MasterKey..".equals(v) || "password".equalsIgnoreCase(v) || "admin123".equalsIgnoreCase(v)) {
            throw new IllegalStateException(name + " es debil o por defecto: usa >=12 caracteres unicos en prod");
        }
    }
}
