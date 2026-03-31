-- 1. name 단일 인덱스
CREATE INDEX IF NOT EXISTS idx_product_name ON product(name);

-- 2. brand_name 단일 인덱스
CREATE INDEX IF NOT EXISTS idx_product_brand_name ON product(brand_name);

-- 3. name + brand_name 복합 인덱스
CREATE INDEX IF NOT EXISTS idx_product_name_brand ON product(name, brand_name);