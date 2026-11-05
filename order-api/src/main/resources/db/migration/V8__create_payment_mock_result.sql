-- Mock 결제 판정 결과. 실제 PG 연동이 아니며, 같은 주문의 결과를 한 번만 판정하기 위한 기록이다.
CREATE TABLE IF NOT EXISTS payment_mock_result (
    order_id       BIGINT         PRIMARY KEY,
    amount         DECIMAL(19, 2) NOT NULL,
    success        BOOLEAN        NOT NULL,
    failure_reason TEXT           NULL,
    processed_at   TIMESTAMPTZ    NOT NULL DEFAULT now()
);
