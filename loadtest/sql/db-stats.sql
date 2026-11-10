-- 각 서비스 DB에서 실행한다. outbox_event 접근 통계와 DB 전체 트랜잭션 수를 한 줄씩 출력한다. 읽기 전용이다.
-- 측정 전후 값의 차이로 Outbox 조회 빈도를 근사한다. 인덱스 스캔에는 Publisher 조회 외의 쿼리(outbox-watch 집계, PK 갱신)도 섞인다.
-- 사용법: psql -At -F'|' -f db-stats.sql
SELECT 'table' AS kind, relname AS name, seq_scan, coalesce(idx_scan, 0), n_tup_ins, n_tup_upd
FROM pg_stat_user_tables WHERE relname = 'outbox_event'
UNION ALL
SELECT 'index', indexrelname, 0, idx_scan, idx_tup_read, idx_tup_fetch
FROM pg_stat_user_indexes WHERE relname = 'outbox_event'
UNION ALL
SELECT 'database', datname, 0, xact_commit, xact_rollback, tup_fetched
FROM pg_stat_database WHERE datname = current_database();
