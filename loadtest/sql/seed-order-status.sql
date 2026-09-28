-- 폴링 부하 테스트용 seed 데이터 (orders + idempotency_request 10,000건)
-- 여러 번 실행해도 같은 결과: 기존 seed 데이터를 지우고 다시 넣는다.
BEGIN;

-- idempotency_request → orders 순으로 삭제 (FK)
DELETE FROM idempotency_request WHERE idem_key LIKE 'seed-%';
DELETE FROM orders WHERE order_no LIKE 'SEED-%';

WITH seeded AS (
    INSERT INTO orders (order_no, user_id, status, total_amount, discount_amount, pay_amount, saga_id)
    SELECT 'SEED-' || lpad(i::text, 6, '0'),
           (i % 1000) + 1,
           CASE
               WHEN i % 10 < 6 THEN 'COMPLETED'
               WHEN i % 10 < 8 THEN 'PAID'
               WHEN i % 10 = 8 THEN 'RESERVED'
               ELSE 'FAILED'
           END,
           10000, 0, 10000,
           gen_random_uuid()
    FROM generate_series(1, 10000) AS s(i)
    RETURNING id, order_no
)
INSERT INTO idempotency_request (idem_key, request_hash, order_id, status)
SELECT 'seed-' || right(order_no, 6),
       md5(order_no),
       id,
       'COMPLETED'
FROM seeded;

COMMIT;

-- 통계 갱신: 대량 삽입 직후 실행 계획이 틀어지지 않게
ANALYZE orders;
ANALYZE idempotency_request;