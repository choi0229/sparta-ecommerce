-- V3: product_variant.sku에 unique constraint 추가
-- 애플리케이션 레벨 중복 체크(existsBySku)로 방어하고 있으나,
-- 동시 요청 경쟁 조건(race condition)에서 중복 저장을 DB 레벨에서도 방지한다.

-- 사전 검증: 기존 데이터에 중복 SKU가 존재하면 migration 중단
DO $$
DECLARE
    dup_count INT;
BEGIN
    SELECT COUNT(*)
    INTO dup_count
    FROM (
        SELECT sku
        FROM product_variant
        GROUP BY sku
        HAVING COUNT(*) > 1
    ) duplicates;

    IF dup_count > 0 THEN
        RAISE EXCEPTION
            'V3 migration 중단: product_variant.sku에 중복 값이 % 건 존재합니다. UNIQUE constraint 추가 전 중복 데이터를 정리하세요.',
            dup_count;
    END IF;
END $$;

ALTER TABLE product_variant
    ADD CONSTRAINT uk_product_variant_sku UNIQUE (sku);
