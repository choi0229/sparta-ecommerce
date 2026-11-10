-- 서버 기준 E2E: 접수 시각(idempotency_request.created_at) → 주문 COMPLETED 시각(orders.updated_at)
-- logistics-api 미실행이라 COMPLETED 이후 orders.updated_at을 바꾸는 갱신(shipment_status)이 없다.
-- 사용법: psql -v run_id=<RUN_ID> -f server-e2e.sql   (orderdb, 읽기 전용)
WITH e2e AS (
    SELECT o.status, extract(epoch FROM o.updated_at - r.created_at) * 1000 AS ms
    FROM idempotency_request r JOIN orders o ON o.id = r.order_id
    WHERE r.idem_key LIKE :'run_id' || '-%'
)
SELECT status, count(*) AS orders,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 0) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 0) AS p95_ms,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 0) AS p99_ms,
       round(min(ms)::numeric, 0) AS min_ms, round(avg(ms)::numeric, 0) AS avg_ms, round(max(ms)::numeric, 0) AS max_ms
FROM e2e GROUP BY status ORDER BY status;
