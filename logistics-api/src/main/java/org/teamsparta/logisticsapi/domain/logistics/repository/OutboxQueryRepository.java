package org.teamsparta.logisticsapi.domain.logistics.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxQueryRepository {

    private final EntityManager entityManager;

    // PENDING 행을 PROCESSING으로 원자적으로 claim한다.
    // claimExpiresAt을 next_retry_at에 기록해 stale 판단 기준으로 재사용한다.
    @SuppressWarnings("unchecked")
    public List<Long> claimIds(ZonedDateTime now, ZonedDateTime claimExpiresAt, int batchSize) {
        String sql = """
                WITH cte AS (
                  SELECT id FROM outbox_event
                  WHERE status = 'PENDING'
                    AND (next_retry_at IS NULL OR next_retry_at <= :now)
                  ORDER BY created_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT :batchSize
                )
                UPDATE outbox_event oe
                SET status = 'PROCESSING',
                    next_retry_at = :claimExpiresAt
                FROM cte
                WHERE oe.id = cte.id
                RETURNING oe.id
                """;

        List<Object> raw = entityManager.createNativeQuery(sql)
                .setParameter("now", now.toOffsetDateTime())
                .setParameter("claimExpiresAt", claimExpiresAt.toOffsetDateTime())
                .setParameter("batchSize", batchSize)
                .getResultList();

        return raw.stream()
                .map(id -> ((Number) id).longValue())
                .toList();
    }

    // next_retry_at이 만료된 PROCESSING 행을 최대 batchSize 건까지 PENDING으로 되돌린다.
    // CTE + LIMIT으로 한 번에 복구하는 양을 제한해 publisher 부하 급증을 방지한다.
    public int recoverStale(ZonedDateTime now, ZonedDateTime nextRetryAt, int batchSize) {
        String sql = """
                WITH cte AS (
                  SELECT id FROM outbox_event
                  WHERE status = 'PROCESSING'
                    AND next_retry_at <= :now
                  ORDER BY next_retry_at
                  FOR UPDATE SKIP LOCKED
                  LIMIT :batchSize
                )
                UPDATE outbox_event oe
                SET status = 'PENDING',
                    retry_count = retry_count + 1,
                    next_retry_at = :nextRetryAt
                FROM cte
                WHERE oe.id = cte.id
                """;

        return entityManager.createNativeQuery(sql)
                .setParameter("now", now.toOffsetDateTime())
                .setParameter("nextRetryAt", nextRetryAt.toOffsetDateTime())
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }

    // 복구 대상 중 retry_count가 임계치 이상인 행 수를 센다.
    // 복구 실행 직전에 호출해 지속 실패 중인 broken row 수를 파악한다.
    public int countStaleHighRetry(ZonedDateTime now, int retryThreshold) {
        String sql = """
                SELECT COUNT(*) FROM outbox_event
                WHERE status = 'PROCESSING'
                  AND next_retry_at <= :now
                  AND retry_count >= :retryThreshold
                """;

        Number result = (Number) entityManager.createNativeQuery(sql)
                .setParameter("now", now.toOffsetDateTime())
                .setParameter("retryThreshold", retryThreshold)
                .getSingleResult();
        return result.intValue();
    }
}
