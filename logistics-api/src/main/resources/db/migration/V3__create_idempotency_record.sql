CREATE TABLE IF NOT EXISTS idempotency_record (
    idem_key    VARCHAR(128) PRIMARY KEY,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
