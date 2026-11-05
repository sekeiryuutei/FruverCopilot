-- V26: Creación de usuario operativo Camilo con acceso autorizado
INSERT INTO app_user (username, password_hash, role, enabled)
VALUES ('camilo', '$2b$12$mPtmfoCObER8BVPBEGUHD.yly5/E.pbeb2Y1ZWMSwcS74o3loaZwi', 'OPERADOR', TRUE)
ON CONFLICT (username) DO UPDATE
SET password_hash = EXCLUDED.password_hash, enabled = TRUE;

