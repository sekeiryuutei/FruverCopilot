-- V10: Zonas de cobertura de domicilios y estado de despacho

CREATE TABLE IF NOT EXISTS delivery_zone (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    covered BOOLEAN NOT NULL DEFAULT TRUE,
    fee NUMERIC(12, 0) NOT NULL DEFAULT 0 CHECK (fee >= 0),
    min_free NUMERIC(12, 0) CHECK (min_free IS NULL OR min_free >= 0),
    estimated_time VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE orders ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS dispatched_by VARCHAR(80);

ALTER TABLE sale ADD COLUMN IF NOT EXISTS dispatched_at TIMESTAMPTZ;
ALTER TABLE sale ADD COLUMN IF NOT EXISTS dispatched_by VARCHAR(80);

-- Semilla de zonas iniciales de ejemplo
INSERT INTO delivery_zone(name, covered, fee, min_free, estimated_time)
VALUES
    ('Zona Centro / Barrios Cercanos', TRUE, 0, NULL, '20-35 min'),
    ('Zona Norte', TRUE, 4000, 40000, '35-50 min'),
    ('Zona Sur / Occidente', TRUE, 5000, 50000, '40-60 min'),
    ('Fuera del perímetro urbano', FALSE, 0, NULL, 'Sin cobertura')
ON CONFLICT (name) DO NOTHING;

