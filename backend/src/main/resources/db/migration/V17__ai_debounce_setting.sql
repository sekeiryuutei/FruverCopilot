-- V17: Tiempo de espera configurable antes de enviar a la IA (Debounce de rafagas)
ALTER TABLE printer_setting
    ADD COLUMN IF NOT EXISTS customer_debounce_seconds INT DEFAULT 20;

UPDATE printer_setting
SET customer_debounce_seconds = 20
WHERE id = 'DEFAULT' AND customer_debounce_seconds IS NULL;

