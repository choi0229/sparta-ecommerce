-- V3: product_variant.sku에 unique constraint 추가
-- 애플리케이션 레벨 중복 체크(existsBySku)로 방어하고 있으나,
-- 동시 요청 경쟁 조건(race condition)에서 중복 저장을 DB 레벨에서도 방지한다.

ALTER TABLE product_variant
    ADD CONSTRAINT uk_product_variant_sku UNIQUE (sku);
