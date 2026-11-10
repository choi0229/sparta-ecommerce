-- Outbox 발행 대기 실측: 이벤트 종류별 created_at → sent_at (측정 구간). 각 서비스 DB에서 실행, 읽기 전용
-- 사용법: psql -v since="'<UTC 시작>'" -v until="'<UTC 종료>'" -f outbox-delay.sql
SELECT event_type, count(*) AS events,
       round(avg(extract(epoch FROM sent_at - created_at))::numeric * 1000, 0) AS avg_ms,
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM sent_at - created_at)))::numeric * 1000, 0) AS p50_ms,
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM sent_at - created_at)))::numeric * 1000, 0) AS p95_ms,
       round(max(extract(epoch FROM sent_at - created_at))::numeric * 1000, 0) AS max_ms
FROM outbox_event
WHERE created_at >= :since AND created_at < :until AND sent_at IS NOT NULL
GROUP BY event_type ORDER BY event_type;
