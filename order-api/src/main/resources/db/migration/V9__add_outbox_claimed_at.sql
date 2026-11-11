-- V9: Outbox claim 구조(PENDING → PROCESSING → SENT) 지원
-- claimed_at: PROCESSING으로 전이한 시각. Publisher가 SENT 처리 전에 중단된 행을 찾는 기준(claimed_at + timeout)이다.
-- 기존 행은 NULL로 남는다(SENT·PENDING·FAILED 행은 claimed_at을 쓰지 않는다). nullable 컬럼 추가라 테이블을 다시 쓰지 않는다.

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ NULL;

-- stale PROCESSING 조회용 부분 인덱스. 기존 행은 PROCESSING이 없으므로 인덱스는 비어 있는 상태로 만들어진다.
CREATE INDEX IF NOT EXISTS idx_outbox_stale_processing
    ON outbox_event (claimed_at)
    WHERE status = 'PROCESSING';
