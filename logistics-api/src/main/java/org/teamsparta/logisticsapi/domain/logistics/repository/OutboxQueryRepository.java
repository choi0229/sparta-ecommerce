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

    // UPDATE...RETURNING으로 SELECT와 상태 전이를 원자적으로 처리한다.
    // FOR UPDATE SKIP LOCKED는 다른 세션이 잠근 행을 건너뛰므로 lock wait이 발생하지 않는다.
    @SuppressWarnings("unchecked")
    public List<Long> claimIds(ZonedDateTime now, int batchSize) {
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
                SET status = 'PROCESSING'
                FROM cte
                WHERE oe.id = cte.id
                RETURNING oe.id
                """;

        List<Object> raw = entityManager.createNativeQuery(sql)
                .setParameter("now", now.toOffsetDateTime())
                .setParameter("batchSize", batchSize)
                .getResultList();

        return raw.stream()
                .map(id -> ((Number) id).longValue())
                .toList();
    }
}
