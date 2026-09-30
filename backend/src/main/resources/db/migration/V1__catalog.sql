CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE TABLE product (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    unit       VARCHAR(10)  NOT NULL CHECK (unit IN ('LB','KG','UNIDAD')),
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_product_name ON product (lower(name));
CREATE INDEX ix_product_name_trgm ON product USING gin (lower(name) gin_trgm_ops);

-- Una fila por (producto, tipo de precio). Agregar RESTAURANTE etc. no requiere migracion estructural.
-- valid_from/valid_to permiten programar precios a futuro sin cambiar el modelo.
CREATE TABLE product_price (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT      NOT NULL REFERENCES product(id),
    price_type VARCHAR(20) NOT NULL,
    amount     NUMERIC(12,0) NOT NULL CHECK (amount >= 0),
    valid_from TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_to   TIMESTAMPTZ,
    UNIQUE (product_id, price_type)
);

CREATE TABLE product_price_history (
    id         BIGSERIAL PRIMARY KEY,
    product_id BIGINT      NOT NULL REFERENCES product(id),
    price_type VARCHAR(20) NOT NULL,
    old_amount NUMERIC(12,0),
    new_amount NUMERIC(12,0) NOT NULL,
    changed_by VARCHAR(80) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_price_history_product ON product_price_history (product_id, changed_at DESC);
