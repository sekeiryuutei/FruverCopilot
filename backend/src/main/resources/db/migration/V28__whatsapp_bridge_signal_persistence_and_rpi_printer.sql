-- V28: Persistencia Signal en PostgreSQL para WhatsApp Bridge y configuración de Impresión Remota Raspberry Pi / Tailscale

-- 1. Credenciales de sesión de Baileys
CREATE TABLE IF NOT EXISTS whatsapp_auth_credentials (
    id VARCHAR(64) PRIMARY KEY,
    creds JSONB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 2. Claves criptográficas Signal (sesiones, pre-keys, sender-keys, app-state)
CREATE TABLE IF NOT EXISTS whatsapp_signal_keys (
    category VARCHAR(64) NOT NULL,
    key_id VARCHAR(128) NOT NULL,
    key_data JSONB NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (category, key_id)
);

CREATE INDEX IF NOT EXISTS ix_whatsapp_signal_keys_category ON whatsapp_signal_keys(category);

-- 3. Bandeja de Entrada (Inbox) de WhatsApp Bridge
CREATE TABLE IF NOT EXISTS whatsapp_bridge_inbox (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL UNIQUE,
    remote_jid VARCHAR(128) NOT NULL,
    from_me BOOLEAN NOT NULL DEFAULT FALSE,
    participant VARCHAR(128),
    push_name VARCHAR(200),
    sender_phone VARCHAR(64),
    message_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    raw_message JSONB,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    decrypted_at TIMESTAMP WITH TIME ZONE,
    processed_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS ix_whatsapp_bridge_inbox_status ON whatsapp_bridge_inbox(status);
CREATE INDEX IF NOT EXISTS ix_whatsapp_bridge_inbox_jid ON whatsapp_bridge_inbox(remote_jid);

-- 4. Bandeja de Salida (Outbox) hacia Spring Boot
CREATE TABLE IF NOT EXISTS whatsapp_bridge_outbox (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(128) NOT NULL,
    inbox_id BIGINT REFERENCES whatsapp_bridge_inbox(id) ON DELETE CASCADE,
    payload JSONB NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_error TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS ix_whatsapp_bridge_outbox_pending ON whatsapp_bridge_outbox(status, next_attempt_at);

-- 5. Parámetros de Impresión Remota Raspberry Pi / Tailscale SSH
ALTER TABLE printer_setting
    ADD COLUMN IF NOT EXISTS rpi_host VARCHAR(100) DEFAULT '100.105.30.91',
    ADD COLUMN IF NOT EXISTS rpi_port INT DEFAULT 22,
    ADD COLUMN IF NOT EXISTS rpi_user VARCHAR(100) DEFAULT 'victor',
    ADD COLUMN IF NOT EXISTS rpi_auth_type VARCHAR(20) DEFAULT 'KEY',
    ADD COLUMN IF NOT EXISTS rpi_key_path VARCHAR(255) DEFAULT '',
    ADD COLUMN IF NOT EXISTS rpi_password VARCHAR(255) DEFAULT '',
    ADD COLUMN IF NOT EXISTS rpi_destination_type VARCHAR(20) DEFAULT 'CUPS',
    ADD COLUMN IF NOT EXISTS rpi_cups_queue VARCHAR(100) DEFAULT 'Brother_HL_L2405W',
    ADD COLUMN IF NOT EXISTS rpi_usb_device VARCHAR(100) DEFAULT '/dev/usb/lp0',
    ADD COLUMN IF NOT EXISTS rpi_tcp_host VARCHAR(100) DEFAULT '192.168.1.50',
    ADD COLUMN IF NOT EXISTS rpi_tcp_port INT DEFAULT 9100;

