-- 재고 DB(inventorydb) 정합성 검증. 읽기 전용이다.
-- 주문 DB와 분리되어 있어 verify.sh가 run_orders 임시 테이블(order_id, status)을 먼저 채운 뒤 실행한다.
-- 결과 형식: 항목|판정|값. 판정: PASS, FAIL, WAIT, INFO
\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'

CREATE TEMP VIEW run_reservations AS
SELECT res.*, ro.status AS order_status
FROM inventory_reservation res JOIN run_orders ro ON ro.order_id = res.order_id;

CREATE TEMP VIEW run_quantities AS
SELECT i.sku,
       coalesce(sum(i.quantity) FILTER (WHERE r.status = 'CONFIRMED'), 0) AS confirmed,
       coalesce(sum(i.quantity) FILTER (WHERE r.status = 'RESERVED'), 0)  AS reserved
FROM inventory_reservation_item i JOIN run_reservations r ON r.id = i.reservation_id
GROUP BY i.sku;

SELECT 'run.reservations.' || status, 'INFO', count(*) FROM run_reservations GROUP BY status ORDER BY 1;

SELECT 'stock.snapshot_exists', CASE WHEN count(*) > 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM loadtest_stock_snapshot WHERE run_id = :'run_id';

-- 재고 보존: 초기 total = 현재 total + 이번 테스트의 확정 수량
SELECT 'stock.total_conservation.' || s.sku,
       CASE WHEN s.total_quantity = c.total_quantity + coalesce(q.confirmed, 0) THEN 'PASS' ELSE 'FAIL' END,
       'initial=' || s.total_quantity || ' current=' || c.total_quantity || ' confirmed=' || coalesce(q.confirmed, 0)
FROM loadtest_stock_snapshot s
JOIN inventory_stock c ON c.sku = s.sku
LEFT JOIN run_quantities q ON q.sku = s.sku
WHERE s.run_id = :'run_id'
ORDER BY s.sku;

-- 예약 보존: 현재 reserved = 초기 reserved + 이번 테스트에서 남은 RESERVED 수량
SELECT 'stock.reserved_conservation.' || s.sku,
       CASE WHEN c.reserved_quantity = s.reserved_quantity + coalesce(q.reserved, 0) THEN 'PASS' ELSE 'FAIL' END,
       'initial=' || s.reserved_quantity || ' current=' || c.reserved_quantity || ' remaining_reserved=' || coalesce(q.reserved, 0)
FROM loadtest_stock_snapshot s
JOIN inventory_stock c ON c.sku = s.sku
LEFT JOIN run_quantities q ON q.sku = s.sku
WHERE s.run_id = :'run_id'
ORDER BY s.sku;

SELECT 'stock.invariant_total_ge_reserved_ge_0', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM inventory_stock c JOIN loadtest_stock_snapshot s ON s.sku = c.sku AND s.run_id = :'run_id'
WHERE c.reserved_quantity < 0 OR c.total_quantity < c.reserved_quantity;

-- SUCCEEDED(주문 COMPLETED) 주문의 예약은 CONFIRMED여야 한다
SELECT 'reservation.completed_order_not_confirmed', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders ro LEFT JOIN inventory_reservation res ON res.order_id = ro.order_id
WHERE ro.status = 'COMPLETED' AND res.status IS DISTINCT FROM 'CONFIRMED';

-- 반대로 재고가 확정됐는데 주문이 실패로 끝나면 안 된다
SELECT 'reservation.confirmed_but_order_failed', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_reservations WHERE status = 'CONFIRMED' AND order_status IN ('FAILED', 'EXPIRED', 'CANCELED');

-- 결제 실패 후 TTL 전까지 예약이 남는 것은 현재 정책이다. 위반이 아니므로 별도 집계한다
SELECT 'reservation.pending_ttl', 'INFO', count(*)
FROM run_reservations WHERE status = 'RESERVED' AND order_status = 'FAILED' AND expires_at > now();

-- 만료 시각이 지났는데 RESERVED면 만료 스케줄러가 처리해야 한다
SELECT 'reservation.expired_not_released', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'WAIT' END, count(*)
FROM run_reservations WHERE status = 'RESERVED' AND expires_at <= now();

SELECT 'inventory.outbox_failed', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM outbox_event WHERE status = 'FAILED'
  AND created_at >= (SELECT min(taken_at) FROM loadtest_stock_snapshot WHERE run_id = :'run_id');

SELECT 'inventory.outbox_backlog', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'WAIT' END, count(*)
FROM outbox_event WHERE status IN ('PENDING', 'PROCESSING');
