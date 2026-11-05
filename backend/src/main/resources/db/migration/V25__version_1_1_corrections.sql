-- V25: Versión 1.1 - Mejoras de facturación, contactos, tiempos e idempotencia de impresión

-- 1. Origen del nombre del cliente para preservar contactos guardados
ALTER TABLE customer ADD COLUMN IF NOT EXISTS name_source VARCHAR(25) NOT NULL DEFAULT 'UNVERIFIED';

-- 2. Tiempo de espera configurable para revisión de pedidos dudosos por parte del operario
ALTER TABLE printer_setting ADD COLUMN IF NOT EXISTS review_timeout_seconds INTEGER NOT NULL DEFAULT 120;

-- 3. Consecutivo comercial independiente para facturas impresas
ALTER TABLE sale ADD COLUMN IF NOT EXISTS invoice_number BIGINT;
CREATE SEQUENCE IF NOT EXISTS invoice_number_seq START 1;

-- Backfill ordenado de ventas existentes para que tengan consecutivo correlativo desde 1
DO $$
DECLARE
    rec RECORD;
    seq_val BIGINT := 1;
BEGIN
    FOR rec IN SELECT id FROM sale ORDER BY created_at ASC, id ASC LOOP
        UPDATE sale SET invoice_number = seq_val WHERE id = rec.id AND invoice_number IS NULL;
        seq_val := seq_val + 1;
    END LOOP;
    IF seq_val > 1 THEN
        PERFORM setval('invoice_number_seq', seq_val);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_sale_invoice_number ON sale (invoice_number);

-- 4. Idempotencia estricta en print_job: una venta solo puede tener un único trabajo inicial (reprint = FALSE)
-- Eliminar posibles duplicados no impresos previos antes de crear el índice único
DELETE FROM print_job a USING print_job b
WHERE a.id > b.id AND a.sale_id = b.sale_id AND a.reprint = FALSE AND b.reprint = FALSE;

CREATE UNIQUE INDEX IF NOT EXISTS ux_print_job_sale_initial ON print_job (sale_id) WHERE reprint = FALSE;

