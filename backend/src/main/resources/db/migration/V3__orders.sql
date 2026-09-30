CREATE SEQUENCE order_number_seq START 10001;

CREATE TABLE orders (
    id                  BIGINT PRIMARY KEY DEFAULT nextval('order_number_seq'),
    customer_id         BIGINT REFERENCES customer(id),
    customer_name       VARCHAR(120),
    customer_phone      VARCHAR(30),
    address             VARCHAR(300),
    notes               VARCHAR(500),
    input_type          VARCHAR(10) NOT NULL CHECK (input_type IN ('TEXT','AUDIO')),
    status              VARCHAR(20) NOT NULL DEFAULT 'RECEIVED'
                        CHECK (status IN ('RECEIVED','PROCESSING','REVIEW','APPROVED','CANCELLED')),
    conversation_status VARCHAR(30) NOT NULL DEFAULT 'READY_FOR_REVIEW'
                        CHECK (conversation_status IN ('WAITING_FOR_CUSTOMER','READY_FOR_REVIEW','READY_FOR_APPROVAL','CONFIRMED','COMPLETED')),
    review_status       VARCHAR(10) CHECK (review_status IN ('GREEN','YELLOW','RED')),
    ai_status           VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                        CHECK (ai_status IN ('PENDING','OK','FAILED','TRANSCRIPTION_FAILED')),
    ai_note             VARCHAR(500),
    suggested_reply     VARCHAR(500),
    corrected           BOOLEAN NOT NULL DEFAULT FALSE,
    version             INTEGER NOT NULL DEFAULT 0,
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    extracted_at        TIMESTAMPTZ,
    approved_at         TIMESTAMPTZ
);
CREATE INDEX ix_orders_status ON orders (status, id DESC);
CREATE INDEX ix_orders_customer_waiting ON orders (customer_id) WHERE conversation_status = 'WAITING_FOR_CUSTOMER';

CREATE TABLE order_item (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    line_no             INTEGER NOT NULL DEFAULT 0,
    mentioned           VARCHAR(200),
    ai_product_name     VARCHAR(200),
    ai_quantity         NUMERIC(10,3),
    ai_unit             VARCHAR(20),
    ai_ambiguous        BOOLEAN NOT NULL DEFAULT FALSE,
    observation         VARCHAR(300),
    product_id          BIGINT REFERENCES product(id),
    quantity            NUMERIC(10,3),
    unit                VARCHAR(10),
    manual_price        NUMERIC(12,0),
    price_change_reason VARCHAR(200),
    edited              BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX ix_order_item_order ON order_item (order_id, line_no);

CREATE TABLE conversation_message (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    direction           VARCHAR(10) NOT NULL CHECK (direction IN ('INBOUND','OUTBOUND')),
    message_type        VARCHAR(10) NOT NULL CHECK (message_type IN ('TEXT','AUDIO','SYSTEM')),
    content             TEXT,
    external_message_id VARCHAR(200) UNIQUE,
    delivery_status     VARCHAR(20),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_msg_order ON conversation_message (order_id, id);

-- El archivo vive fuera de PostgreSQL; aqui solo metadatos y la referencia (storage_key).
CREATE TABLE audio (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    message_id       BIGINT NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    media_ref        VARCHAR(200),
    storage_key      VARCHAR(200),
    mime_type        VARCHAR(100),
    duration_seconds INTEGER,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audio_order ON audio (order_id);

CREATE TABLE transcription (
    id         BIGSERIAL PRIMARY KEY,
    audio_id   BIGINT REFERENCES audio(id) ON DELETE CASCADE,
    message_id BIGINT NOT NULL REFERENCES conversation_message(id) ON DELETE CASCADE,
    text       TEXT NOT NULL,
    model      VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
