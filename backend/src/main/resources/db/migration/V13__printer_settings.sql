-- V13: Configuracion dinamica de impresoras termicas duales (Oficina / Casa)
CREATE TABLE IF NOT EXISTS printer_setting (
    id VARCHAR(30) PRIMARY KEY,
    office_host VARCHAR(120) NOT NULL DEFAULT '127.0.0.1',
    office_port INT NOT NULL DEFAULT 9100,
    home_host VARCHAR(120) NOT NULL DEFAULT '127.0.0.1',
    home_port INT NOT NULL DEFAULT 9100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    mode VARCHAR(20) NOT NULL DEFAULT 'AUTO',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

INSERT INTO printer_setting(id, office_host, office_port, home_host, home_port, enabled, mode)
VALUES ('DEFAULT', '127.0.0.1', 9100, '127.0.0.1', 9100, TRUE, 'AUTO')
ON CONFLICT (id) DO NOTHING;
