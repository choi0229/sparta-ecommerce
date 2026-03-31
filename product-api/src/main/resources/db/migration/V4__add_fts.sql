-- tsvector 컬럼 추가
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='product' AND column_name='name_tsv') THEN
ALTER TABLE product ADD COLUMN name_tsv tsvector;
END IF;
END $$;

-- 2. brand_tsv 컬럼 추가 (존재 여부 체크 후 실행)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='product' AND column_name='brand_tsv') THEN
ALTER TABLE product ADD COLUMN brand_tsv tsvector;
END IF;
END $$;

-- 데이터 채우기 (10만건이라 좀 걸릴 수 있어요)
--UPDATE product SET
--                   name_tsv = to_tsvector('simple', name),
--                   brand_tsv = to_tsvector('simple', brand_name);

-- GIN 인덱스 생성
CREATE INDEX IF NOT EXISTS idx_product_name_fts ON product USING GIN(name_tsv);
CREATE INDEX IF NOT EXISTS idx_product_brand_fts ON product USING GIN(brand_tsv);