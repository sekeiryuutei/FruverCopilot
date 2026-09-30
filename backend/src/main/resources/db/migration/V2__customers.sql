CREATE TABLE customer (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(120),
    phone         VARCHAR(30) NOT NULL UNIQUE,
    customer_type VARCHAR(20) NOT NULL DEFAULT 'RETAIL' CHECK (customer_type IN ('RETAIL','WHOLESALE')),
    address       VARCHAR(300),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
