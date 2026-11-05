-- 재고 DB(inventorydb)에서 실행한다. 부하 테스트 SKU의 재고를 늘린다. 기존 값을 덮어쓰거나 지우지 않는다.
-- 사용법: psql -v skus='LOADTEST-SKU-A,LOADTEST-SKU-B' -v add=20000 -f seed-stock.sql
UPDATE inventory_stock
SET total_quantity = total_quantity + :add,
    updated_at = now()
WHERE sku = ANY (string_to_array(:'skus', ','));

SELECT sku, total_quantity, reserved_quantity, total_quantity - reserved_quantity AS available
FROM inventory_stock
WHERE sku = ANY (string_to_array(:'skus', ','))
ORDER BY sku;
