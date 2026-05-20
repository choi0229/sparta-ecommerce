-- =========================================================
-- 1) product_projection (snapshot)
-- =========================================================
CREATE TABLE IF NOT EXISTS product_projection(
    sku             VARCHAR(80) PRIMARY KEY,
    variant_id      BIGINT NOT NULL UNIQUE,
    product_id      BIGINT NOT NULL,
    price           DECIMAL(19, 2)NOT NULL,
    status          VARCHAR(20)NOT NULL,
    product_name    VARCHAR(200) NOT NULL,
    option_json     jsonb NULL,
    category_path   VARCHAR(512),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_projection_sku ON product_projection(sku);