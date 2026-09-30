package com.fruver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Crea el administrador (y opcionalmente un operador) desde variables de entorno si aun no existen. */
@Component
public class BootstrapUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapUsers.class);
    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AppProperties props;
    private final Environment env;

    public BootstrapUsers(JdbcClient jdbc, PasswordEncoder encoder, AppProperties props, Environment env) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.props = props;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Security s = props.security();
        if ("change_me".equals(s.adminPassword())) {
            if (env.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("ADMIN_PASSWORD no puede ser 'change_me' en produccion");
            }
            log.warn("ADMIN_PASSWORD usa el valor por defecto: cambialo antes de produccion");
        }
        ensure(s.adminUsername(), s.adminPassword(), "ADMIN");
        if (s.operatorPassword() != null && !s.operatorPassword().isBlank()) {
            ensure(s.operatorUsername(), s.operatorPassword(), "OPERADOR");
        }
    }

    private void ensure(String username, String password, String role) {
        int created = jdbc.sql("INSERT INTO app_user(username, password_hash, role) VALUES (:u, :p, :r) ON CONFLICT DO NOTHING")
                .param("u", username).param("p", encoder.encode(password)).param("r", role).update();
        if (created > 0) log.info("Usuario {} ({}) creado", username, role);
    }
}
