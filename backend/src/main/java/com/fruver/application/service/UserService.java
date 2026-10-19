package com.fruver.application.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class UserService {

    public record UserView(String username, String role, boolean enabled, OffsetDateTime createdAt) {}

    private final JdbcClient jdbc;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public UserService(JdbcClient jdbc, PasswordEncoder encoder, AuditService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    public List<UserView> list() {
        return jdbc.sql("SELECT username, role, enabled, COALESCE(created_at, now()) FROM app_user ORDER BY username ASC")
                .query((rs, n) -> new UserView(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getObject(4, OffsetDateTime.class))).list();
    }

    @Transactional
    public UserView create(String username, String password, String role, String actor) {
        if (username == null || username.isBlank() || username.length() < 3 || username.length() > 80) {
            throw new ApiException(400, "VALIDATION_ERROR", "El nombre de usuario debe tener entre 3 y 80 caracteres");
        }
        if (password == null || password.length() < 6) {
            throw new ApiException(400, "VALIDATION_ERROR", "La contraseña debe tener al menos 6 caracteres");
        }
        String cleanRole = role != null && role.equalsIgnoreCase("ADMIN") ? "ADMIN" : "OPERADOR";
        String cleanUser = username.trim().toLowerCase();

        int exists = jdbc.sql("SELECT count(*) FROM app_user WHERE username = :u").param("u", cleanUser).query(Integer.class).single();
        if (exists > 0) throw new ApiException(409, "USER_EXISTS", "El usuario ya existe");

        jdbc.sql("""
                INSERT INTO app_user(username, password_hash, role, enabled, created_at)
                VALUES (:u, :p, :r, TRUE, now())
                """).param("u", cleanUser).param("p", encoder.encode(password)).param("r", cleanRole).update();

        audit.log(actor, "USER_CREATED", null, null, Map.of("username", cleanUser, "role", cleanRole));
        return new UserView(cleanUser, cleanRole, true, OffsetDateTime.now());
    }

    @Transactional
    public void updatePassword(String username, String newPassword, String actor) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new ApiException(400, "VALIDATION_ERROR", "La nueva contraseña debe tener al menos 6 caracteres");
        }
        String cleanUser = username.trim().toLowerCase();
        int n = jdbc.sql("UPDATE app_user SET password_hash = :p WHERE username = :u")
                .param("p", encoder.encode(newPassword)).param("u", cleanUser).update();
        if (n == 0) throw new ApiException(404, "USER_NOT_FOUND", "Usuario no encontrado");
        audit.log(actor, "USER_PASSWORD_UPDATED", null, null, Map.of("username", cleanUser));
    }

    @Transactional
    public void setEnabled(String username, boolean enabled, String actor) {
        String cleanUser = username.trim().toLowerCase();
        if (cleanUser.equalsIgnoreCase(actor) && !enabled) {
            throw new ApiException(400, "CANNOT_DISABLE_SELF", "No puedes desactivar tu propia cuenta");
        }
        int n = jdbc.sql("UPDATE app_user SET enabled = :e WHERE username = :u")
                .param("e", enabled).param("u", cleanUser).update();
        if (n == 0) throw new ApiException(404, "USER_NOT_FOUND", "Usuario no encontrado");
        audit.log(actor, "USER_ENABLED_CHANGED", null, null, Map.of("username", cleanUser, "enabled", enabled));
    }

    @Transactional
    public void delete(String username, String actor) {
        String cleanUser = username.trim().toLowerCase();
        if (cleanUser.equalsIgnoreCase(actor)) {
            throw new ApiException(400, "CANNOT_DELETE_SELF", "No puedes eliminar tu propia cuenta");
        }
        int adminCount = jdbc.sql("SELECT count(*) FROM app_user WHERE role = 'ADMIN' AND enabled = TRUE").query(Integer.class).single();
        String currentRole = jdbc.sql("SELECT role FROM app_user WHERE username = :u").param("u", cleanUser).query(String.class).optional().orElse(null);
        if ("ADMIN".equals(currentRole) && adminCount <= 1) {
            throw new ApiException(400, "LAST_ADMIN", "No puedes eliminar al único administrador del sistema");
        }
        jdbc.sql("DELETE FROM app_user WHERE username = :u").param("u", cleanUser).update();
        audit.log(actor, "USER_DELETED", null, null, Map.of("username", cleanUser));
    }
}

