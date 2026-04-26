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

    // next_retry_at이 만료된 PROCESSING 행을 PENDING으로 되돌린다.
    public int recoverStale(ZonedDateTime now, ZonedDateTime nextRetryAt) {
        String sql = """
                UPDATE outbox_event
                SET status = 'PENDING',
                    retry_count = retry_count + 1,
                    next_retry_at = :nextRetryAt
                WHERE status = 'PROCESSING'
                  AND next_retry_at <= :now
                """;

        return entityManager.createNativeQuery(sql)
                .setParameter("now", now.toOffsetDateTime())
                .setParameter("nextRetryAt", nextRetryAt.toOffsetDateTime())
                .executeUpdate();
    }
}
