CREATE TABLE print_job (
    id              BIGSERIAL PRIMARY KEY,
    sale_id         BIGINT NOT NULL REFERENCES sale(id),
    reprint         BOOLEAN NOT NULL DEFAULT FALSE,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PRINTING','DONE','FAILED')),
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      VARCHAR(300),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_print_job_pending ON print_job (status, next_attempt_at);

CREATE TABLE audit_log (
    id       BIGSERIAL PRIMARY KEY,
    at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    username VARCHAR(80) NOT NULL,
    action   VARCHAR(60) NOT NULL,
    order_id BIGINT,
    sale_id  BIGINT,
    detail   JSONB
);
CREATE INDEX ix_audit_order ON audit_log (order_id, at);

CREATE TABLE app_user (
    username      VARCHAR(80) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','OPERADOR')),
    enabled       BOOLEAN NOT NULL DEFAULT TRUE
);
