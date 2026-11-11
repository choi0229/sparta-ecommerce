package org.teamsparta.inventoryapi.domain.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;

import java.time.ZonedDateTime;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    long countByStatus(OutboxStatus status);

    /**
     * staleThreshold보다 오래된 PROCESSING row의 ID를 FOR UPDATE SKIP LOCKED로 조회한다.
     * publisher 크래시 복구(stale PROCESSING → PENDING)에 사용한다.
     * 호출부는 반드시 @Transactional 컨텍스트 안에 있어야 한다.
     */
    @Query(value = """
            SELECT id FROM outbox_event
            WHERE status = 'PROCESSING'
              AND claimed_at < :staleThreshold
            ORDER BY claimed_at ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findStaleProcessingIds(
            @Param("staleThreshold") ZonedDateTime staleThreshold,
            @Param("limit") int limit);

    /**
     * 한 행을 조건부로 claim한다: PENDING이고 재시도 시각이 된 경우에만 PROCESSING으로 바꾼다.
     * 폴러가 먼저 claim했거나(PROCESSING/SENT) 재시도 대기 중이면 0을 반환한다.
     * 폴러가 FOR UPDATE로 잠근 중이면 그 트랜잭션이 끝날 때까지 기다린 뒤 조건을 다시 평가한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE outbox_event
            SET status = 'PROCESSING', claimed_at = :now
            WHERE id = :id
              AND status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= :now)
            """, nativeQuery = true)
    int claimIfPending(@Param("id") Long id, @Param("now") ZonedDateTime now);
}
