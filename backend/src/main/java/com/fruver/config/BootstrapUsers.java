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
        boolean prod = env.acceptsProfiles(Profiles.of("prod"));
        if ("change_me".equals(s.adminPassword()) || "MasterKey..".equals(s.adminPassword())) {
            if (prod) {
                throw new IllegalStateException("ADMIN_PASSWORD no puede ser valor por defecto en produccion");
            }
            log.warn("ADMIN_PASSWORD usa valor por defecto/debil: cambialo antes de produccion");
        } else if (s.adminPassword() == null || s.adminPassword().length() < 12) {
            if (prod) throw new IllegalStateException("ADMIN_PASSWORD debe tener >=12 caracteres en produccion");
            log.warn("ADMIN_PASSWORD debil (<12 caracteres): usalo solo en desarrollo");
        }
        if (s.operatorPassword() != null && !s.operatorPassword().isBlank()
                && s.operatorPassword().equals(s.adminPassword())) {
            if (prod) throw new IllegalStateException("OPERATOR_PASSWORD no puede igualar ADMIN_PASSWORD en prod");
            log.warn("OPERATOR_PASSWORD igual a ADMIN_PASSWORD: usa claves distintas");
        }
        ensure(s.adminUsername(), s.adminPassword(), "ADMIN");
        if (s.operatorPassword() != null && !s.operatorPassword().isBlank()) {
            ensure(s.operatorUsername(), s.operatorPassword(), "OPERADOR");
        }
    }

    private void ensure(String username, String password, String role) {
        int created = jdbc.sql("""
                INSERT INTO app_user(username, password_hash, role) VALUES (:u, :p, :r)
                ON CONFLICT (username) DO UPDATE SET password_hash = EXCLUDED.password_hash
                """).param("u", username).param("p", encoder.encode(password)).param("r", role).update();
        if (created > 0) log.info("Usuario {} ({}) sincronizado", username, role);
    }
}
