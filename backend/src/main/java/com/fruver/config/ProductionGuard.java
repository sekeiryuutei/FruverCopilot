package com.fruver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** La app se niega a arrancar en produccion con configuraciones de desarrollo peligrosas. */
@Component
public class ProductionGuard {

    public ProductionGuard(Environment env, AppProperties props,
                           @Value("${spring.ai.openai.chat.options.model:}") String aiModel) {
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
    }
}
