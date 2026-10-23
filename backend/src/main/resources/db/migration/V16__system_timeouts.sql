-- V16: Configuracion dinamica de tiempos de espera para pedidos e impresion
ALTER TABLE printer_setting
    ADD COLUMN IF NOT EXISTS order_grace_seconds INT,
    ADD COLUMN IF NOT EXISTS print_timeout_seconds INT,
    ADD COLUMN IF NOT EXISTS auto_print_wholesale BOOLEAN;

