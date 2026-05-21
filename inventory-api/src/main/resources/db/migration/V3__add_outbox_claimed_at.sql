-- V3: PROCESSING claim 구조 지원을 위한 claimed_at 컬럼 추가
-- PROCESSING 상태의 row를 대상으로 stale recovery 시 사용 (claimed_at + timeout)

ALTER TABLE outbox_event
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ NULL;

-- stale PROCESSING 조회 최적화 인덱스
CREATE INDEX IF NOT EXISTS idx_outbox_stale_processing
    ON outbox_event (claimed_at)
    WHERE status = 'PROCESSING';
