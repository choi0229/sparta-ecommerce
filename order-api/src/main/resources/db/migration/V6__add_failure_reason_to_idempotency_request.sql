ALTER TABLE idempotency_request
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(512) NULL;
