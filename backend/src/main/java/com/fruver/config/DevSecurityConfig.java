package com.fruver.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cadena de seguridad SOLO para /api/dev/** y SOLO si DEV_ENDPOINTS=true.
 * Sin CSRF (para curl/Postman/DevTools) pero SIEMPRE con credenciales: HTTP Basic (usuario y clave de la app)
 * o la sesion del navegador. Nunca permitAll: con ngrok esta ruta es alcanzable desde Internet.
 */
@Configuration
@ConditionalOnProperty(name = "app.dev-endpoints", havingValue = "true")
public class DevSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/dev/**")
            .csrf(c -> c.disable())
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
