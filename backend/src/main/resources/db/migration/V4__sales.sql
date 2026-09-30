-- La venta es el resultado confirmado. Todo lo comercial queda congelado (snapshot).
CREATE TABLE sale (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL UNIQUE REFERENCES orders(id),
    customer_id    BIGINT REFERENCES customer(id),
    customer_name  VARCHAR(120),
    customer_phone VARCHAR(30),
    address        VARCHAR(300),
    notes          VARCHAR(500),
    customer_type  VARCHAR(20) NOT NULL,
    price_type     VARCHAR(20) NOT NULL,
    input_type     VARCHAR(10) NOT NULL,
    currency       CHAR(3)     NOT NULL DEFAULT 'COP',
    total          NUMERIC(14,0) NOT NULL,
    print_status   VARCHAR(20) NOT NULL DEFAULT 'PRINT_PENDING'
                   CHECK (print_status IN ('PRINT_PENDING','PRINTED','PRINT_FAILED')),
    print_error    VARCHAR(300),
    approved_by    VARCHAR(80) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    printed_at     TIMESTAMPTZ
);
CREATE INDEX ix_sale_created ON sale (created_at DESC);

CREATE TABLE sale_item (
    id                     BIGSERIAL PRIMARY KEY,
    sale_id                BIGINT NOT NULL REFERENCES sale(id),
    product_id             BIGINT NOT NULL REFERENCES product(id),
    product_name           VARCHAR(120) NOT NULL,
    unit                   VARCHAR(10)  NOT NULL,
    quantity               NUMERIC(10,3) NOT NULL,
    price_type             VARCHAR(20)  NOT NULL,
    catalog_price          NUMERIC(12,0),
    applied_price          NUMERIC(12,0) NOT NULL,
    price_manually_changed BOOLEAN NOT NULL DEFAULT FALSE,
    price_change_reason    VARCHAR(200),
    subtotal               NUMERIC(14,0) NOT NULL
);
CREATE INDEX ix_sale_item_sale ON sale_item (sale_id);
CREATE INDEX ix_sale_item_product ON sale_item (product_id);
