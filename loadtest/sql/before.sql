-- 재고 DB(inventorydb). 부하 테스트 시작 전에 SKU별 재고를 runId로 저장한다.
-- 사용법: psql -v run_id=rps10 -v skus='LOADTEST-SKU-A,LOADTEST-SKU-B' -f before.sql
-- 같은 run_id로 다시 실행해도 처음 스냅샷을 덮어쓰지 않는다.
CREATE TABLE IF NOT EXISTS loadtest_stock_snapshot (
    run_id            VARCHAR(64) NOT NULL,
    sku               VARCHAR(64) NOT NULL,
    total_quantity    INTEGER     NOT NULL,
    reserved_quantity INTEGER     NOT NULL,
    taken_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, sku)
);

INSERT INTO loadtest_stock_snapshot (run_id, sku, total_quantity, reserved_quantity)
SELECT :'run_id', sku, total_quantity, reserved_quantity
FROM inventory_stock
WHERE sku = ANY (string_to_array(:'skus', ','))
ON CONFLICT (run_id, sku) DO NOTHING;

SELECT run_id, sku, total_quantity, reserved_quantity, taken_at
FROM loadtest_stock_snapshot
WHERE run_id = :'run_id'
ORDER BY sku;
