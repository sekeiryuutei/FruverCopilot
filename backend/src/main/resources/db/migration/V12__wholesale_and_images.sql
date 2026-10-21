-- V12: Pipeline Mayorista, Soporte de Imagenes (OCR Vision) y Doble Impresora (Oficina vs Casa)

-- 1) Permitir 'IMAGE' y 'MANUAL' en orders y conversation_message
ALTER TABLE orders DROP CONSTRAINT IF EXISTS orders_input_type_check;
ALTER TABLE orders ADD CONSTRAINT orders_input_type_check CHECK (input_type IN ('TEXT','AUDIO','IMAGE','MANUAL'));

ALTER TABLE conversation_message DROP CONSTRAINT IF EXISTS conversation_message_message_type_check;
ALTER TABLE conversation_message ADD CONSTRAINT conversation_message_message_type_check CHECK (message_type IN ('TEXT','AUDIO','IMAGE','SYSTEM','INVOICE'));

-- 2) Metadatos de imagenes recibidas (fotos de listas manuscritas, capturas, libretas)
CREATE TABLE IF NOT EXISTS media_image (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    message_id       BIGINT NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    media_ref        VARCHAR(200),
    storage_key      VARCHAR(200),
    mime_type        VARCHAR(100),
    caption          TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_media_image_order ON media_image (order_id);

-- 3) Flexibilizar sale y sale_item para comanda mayorista (articulos sin precio fijo de catalogo)
ALTER TABLE sale ALTER COLUMN total DROP NOT NULL;
ALTER TABLE sale DROP CONSTRAINT IF EXISTS ck_sale_total;
ALTER TABLE sale ADD CONSTRAINT ck_sale_total CHECK (total IS NULL OR total >= 0);

ALTER TABLE sale_item ALTER COLUMN product_id DROP NOT NULL;
ALTER TABLE sale_item ALTER COLUMN applied_price DROP NOT NULL;
ALTER TABLE sale_item ALTER COLUMN subtotal DROP NOT NULL;
ALTER TABLE sale_item DROP CONSTRAINT IF EXISTS ck_sale_item_prices;
ALTER TABLE sale_item ADD CONSTRAINT ck_sale_item_prices CHECK ((applied_price IS NULL AND subtotal IS NULL) OR (applied_price >= 0 AND subtotal >= 0));

-- 4) Impresoras duales (OFICINA vs CASA) y estado de verificación
ALTER TABLE print_job ADD COLUMN IF NOT EXISTS target_printer VARCHAR(30) DEFAULT 'OFICINA';
ALTER TABLE print_job ADD COLUMN IF NOT EXISTS printed_printer VARCHAR(30);

ALTER TABLE sale ADD COLUMN IF NOT EXISTS target_printer VARCHAR(30);
ALTER TABLE sale ADD COLUMN IF NOT EXISTS printed_printer VARCHAR(30);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS requires_verification BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS verification_reason VARCHAR(300);

ALTER TABLE sale ADD COLUMN IF NOT EXISTS requires_verification BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sale ADD COLUMN IF NOT EXISTS verification_reason VARCHAR(300);

