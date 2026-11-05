-- 주문 DB(orderdb). 이번 테스트의 주문을 재고 DB 검증에 넘기기 위해 CSV로 출력한다.
COPY (
    SELECT o.id, o.status
    FROM idempotency_request r JOIN orders o ON o.id = r.order_id
    WHERE r.idem_key LIKE :'run_id' || '-%'
) TO STDOUT WITH (FORMAT csv);
