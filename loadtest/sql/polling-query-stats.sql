-- 측정 시작 직전: SELECT pg_stat_statements_reset();
-- 측정 종료 직후: 아래 쿼리 실행. QPS = calls / 측정 시간(초)
SELECT query,
       calls,
       round(mean_exec_time::numeric, 3)  AS mean_ms,
       round(total_exec_time::numeric, 1) AS total_ms,
       rows
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND (query ILIKE '%from idempotency_request%' OR query ILIKE '%from orders%')
ORDER BY calls DESC;
