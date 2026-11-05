-- 각 서비스 DB에서 실행한다. Outbox의 초당 발행량과 발행 지연을 본다. 읽기 전용이다.
-- 사용법: psql -v since="'2026-01-01 00:00:00+09'" -f outbox-throughput.sql
SELECT date_trunc('second', sent_at)                                     AS second,
       count(*)                                                          AS sent,
       round(avg(extract(epoch FROM sent_at - created_at))::numeric, 3)  AS avg_delay_s,
       round(max(extract(epoch FROM sent_at - created_at))::numeric, 3)  AS max_delay_s
FROM outbox_event
WHERE sent_at >= :since
GROUP BY 1
ORDER BY 1;

SELECT status, count(*) FROM outbox_event GROUP BY status ORDER BY status;
