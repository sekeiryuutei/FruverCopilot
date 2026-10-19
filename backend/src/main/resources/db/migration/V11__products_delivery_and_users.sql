-- V11: Imagenes de productos, repartidores, asignacion y soporte de usuarios

-- 1) Imagen en productos
ALTER TABLE product ADD COLUMN IF NOT EXISTS image_url TEXT;

-- 2) Repartidores / Domiciliarios
CREATE TABLE IF NOT EXISTS delivery_person (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    phone VARCHAR(30),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Semilla de repartidores de ejemplo
INSERT INTO delivery_person (name, phone) VALUES
    ('Carlos Gómez (Moto 1)', '3101112233'),
    ('Andrés Rodríguez (Moto 2)', '3154445566')
ON CONFLICT (name) DO NOTHING;

-- 3) Asignacion de repartidor en pedidos y ventas
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_person_id BIGINT REFERENCES delivery_person(id);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS delivery_person_name VARCHAR(120);

ALTER TABLE sale ADD COLUMN IF NOT EXISTS delivery_person_id BIGINT REFERENCES delivery_person(id);
ALTER TABLE sale ADD COLUMN IF NOT EXISTS delivery_person_name VARCHAR(120);

-- 4) Fecha de creacion en app_user
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();

