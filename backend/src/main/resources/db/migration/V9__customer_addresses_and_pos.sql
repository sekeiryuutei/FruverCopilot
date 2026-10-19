-- Permitir pedidos manuales (creados en mostrador o por llamada por vendedores)
ALTER TABLE orders DROP CONSTRAINT orders_input_type_check;
ALTER TABLE orders ADD CONSTRAINT orders_input_type_check CHECK (input_type IN ('TEXT','AUDIO','MANUAL'));

-- Tabla de direcciones estructuradas y etiquetadas por cliente (Casa, Apartamento, Negocio, etc.)
CREATE TABLE customer_address (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES customer(id) ON DELETE CASCADE,
    label       VARCHAR(60) NOT NULL DEFAULT 'Casa',
    address     VARCHAR(300) NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_customer_address_customer ON customer_address(customer_id);

-- Migrar direcciones existentes en customer
INSERT INTO customer_address (customer_id, label, address, is_default)
SELECT id, 'Principal', address, TRUE FROM customer
WHERE address IS NOT NULL AND trim(address) <> '';

