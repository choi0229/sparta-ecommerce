package org.teamsparta.inventoryapi.domain.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
