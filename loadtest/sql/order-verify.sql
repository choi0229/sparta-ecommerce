-- 주문 DB(orderdb) 정합성 검증. 읽기 전용이다.
-- 이번 테스트의 주문은 멱등키 접두사(<run_id>-)로 식별한다. 결과 형식: 항목|판정|값
-- 판정: PASS, FAIL(정합성 위반), WAIT(아직 처리 중이라 판단 보류), INFO(참고)
\set ON_ERROR_STOP on
\pset tuples_only on
\pset format unaligned
\pset fieldsep '|'

CREATE TEMP VIEW run_requests AS
SELECT * FROM idempotency_request WHERE idem_key LIKE :'run_id' || '-%';

CREATE TEMP VIEW run_orders AS
SELECT o.*, r.idem_key
FROM run_requests r JOIN orders o ON o.id = r.order_id;

SELECT 'run.requests', 'INFO', count(*) FROM run_requests;
SELECT 'run.requests.' || status, 'INFO', count(*) FROM run_requests GROUP BY status ORDER BY 1;
SELECT 'run.orders.' || status, 'INFO', count(*) FROM run_orders GROUP BY status ORDER BY 1;

-- 처리 중인 건이 있으면 재고 보존 등 나머지 판단이 달라질 수 있다
SELECT 'order.pending_requests', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'WAIT' END, count(*)
FROM run_requests WHERE status = 'PENDING';
SELECT 'order.in_progress', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'WAIT' END, count(*)
FROM run_orders WHERE status NOT IN ('COMPLETED', 'FAILED', 'EXPIRED', 'CANCELED');

-- 멱등키당 주문 1건: PK로 키 중복은 불가능하므로, 한 주문을 여러 키가 가리키는지 본다
SELECT 'order.order_shared_by_keys', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM (SELECT order_id FROM run_requests WHERE order_id IS NOT NULL GROUP BY order_id HAVING count(*) > 1) t;

SELECT 'order.duplicate_order_no', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM (SELECT order_no FROM orders GROUP BY order_no HAVING count(*) > 1) t;

-- 고아 주문: 이번 테스트 시간대에 만들어졌는데 고객 멱등 기록이 가리키지 않는 주문.
-- 배송 이벤트 기록(shipment-event:)은 주문 접수 기록이 아니므로 제외한다. 다른 트래픽이 섞이면 오탐할 수 있다.
SELECT 'order.orphan_orders', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM orders o
WHERE o.created_at BETWEEN (SELECT min(created_at) FROM run_requests) AND (SELECT max(updated_at) FROM run_requests)
  AND NOT EXISTS (SELECT 1 FROM idempotency_request r
                  WHERE r.order_id = o.id AND r.idem_key NOT LIKE 'shipment-event:%');

SELECT 'order.completed_request_without_order', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_requests r
WHERE r.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.id = r.order_id);

SELECT 'order.failed_request_with_order', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_requests WHERE status = 'FAILED' AND order_id IS NOT NULL;

SELECT 'order.failed_request_without_reason', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_requests WHERE status = 'FAILED' AND coalesce(failure_reason, '') = '';

SELECT 'order.failed_order_without_reason', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders WHERE status IN ('FAILED', 'EXPIRED') AND coalesce(failure_reason, '') = '';

SELECT 'order.saga_mismatch', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders o LEFT JOIN order_saga_state s ON s.order_id = o.id
WHERE s.saga_id IS NULL
   OR (o.status = 'COMPLETED' AND s.state <> 'COMPLETED')
   OR (o.status = 'FAILED' AND s.state <> 'FAILED')
   OR (o.status = 'EXPIRED' AND s.state <> 'EXPIRED');

SELECT 'order.amount_mismatch', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders o
LEFT JOIN (SELECT order_id, sum(price_snapshot * quantity) AS total FROM order_item GROUP BY order_id) i ON i.order_id = o.id
WHERE i.total IS NULL OR o.total_amount <> i.total OR o.pay_amount <> o.total_amount - o.discount_amount;

SELECT 'order.payment_amount_mismatch', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders o JOIN payment_mock_result p ON p.order_id = o.id
WHERE p.amount <> o.pay_amount;

-- 완료 주문은 결제 성공 기록이, 결제 실패 기록의 주문은 완료가 아니어야 한다
SELECT 'order.completed_without_payment_success', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders o LEFT JOIN payment_mock_result p ON p.order_id = o.id
WHERE o.status = 'COMPLETED' AND p.success IS NOT TRUE;

SELECT 'order.declined_but_progressed', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM run_orders o JOIN payment_mock_result p ON p.order_id = o.id
WHERE p.success = false AND o.status IN ('PAID', 'COMPLETED');

SELECT 'run.payment.declined', 'INFO', count(*)
FROM run_orders o JOIN payment_mock_result p ON p.order_id = o.id WHERE p.success = false;

-- 재시도를 소진한 Outbox 이벤트. 해당 주문이 진행하지 못했을 수 있으므로 위반으로 본다
SELECT 'order.outbox_failed', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'FAIL' END, count(*)
FROM outbox_event WHERE status = 'FAILED' AND created_at >= (SELECT min(created_at) FROM run_requests);

SELECT 'order.outbox_backlog', CASE WHEN count(*) = 0 THEN 'PASS' ELSE 'WAIT' END, count(*)
FROM outbox_event WHERE status = 'PENDING';
