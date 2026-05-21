package org.teamsparta.inventoryapi.domain.inventory.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxQueryRepository;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * PENDING → PROCESSING claim 과 stale PROCESSING → PENDING 복구를 담당한다.
 *
 * <p>claimBatch()는 짧은 단일 트랜잭션 안에서 PESSIMISTIC_WRITE 락 획득 → PROCESSING 전이 →
 * 커밋까지 완료한다. Kafka 전송은 이 트랜잭션 밖에서 OutboxPublisherJob이 처리한다.
 *
 * <p>stale PROCESSING recovery: publisher 크래시로 인해 PROCESSING 상태가 고착되면
 * claimed_at 기준 timeout 이후 recoverStaleProcessing()이 PENDING으로 재설정한다.
 * 이때 retryCount는 건드리지 않는다(전송 실패가 아닌 publisher 장애이므로).
 *
 * <p>알려진 한계: PROCESSING 전이 직후 publisher 인스턴스가 크래시되면 timeout(기본 5분)
 * 만큼 이벤트 발행이 지연될 수 있다. 이 지연을 줄이려면 timeout을 줄이거나
 * claimed_at heartbeat 갱신 로직을 추가해야 한다(현재 PR 범위 밖).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxEventClaimer {

    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxEventRepository outboxEventRepository;

    /**
     * PENDING 이벤트를 PROCESSING으로 claim한다.
     *
     * <p>조회 ~ 상태 전이 ~ 저장이 단일 트랜잭션 안에서 끝난다.
     * PESSIMISTIC_WRITE 락은 이 트랜잭션이 커밋될 때 해제된다.
     * Kafka 전송은 호출부(OutboxPublisherJob)에서 트랜잭션 밖에서 수행한다.
     *
     * @param now       조회 기준 시각 (nextRetryAt 비교에 사용)
     * @param limit     한 번에 claim할 최대 건수
     * @return PROCESSING 상태로 전이된 이벤트 목록 (엔티티는 detached이지만 ID와 필드는 유효)
     */
    @Transactional
    public List<OutboxEvent> claimBatch(ZonedDateTime now, int limit) {
        List<OutboxEvent> batch = outboxQueryRepository.findBatchForPublish(now, limit);
        if (batch.isEmpty()) {
            return batch;
        }
        ZonedDateTime claimedAt = ZonedDateTime.now();
        for (OutboxEvent event : batch) {
            event.markProcessing(claimedAt);
        }
        outboxEventRepository.saveAll(batch);
        return batch;
    }

    /**
     * staleThreshold보다 오래된 PROCESSING 이벤트를 PENDING으로 되돌린다.
     *
     * <p>publisher 크래시로 인해 PROCESSING이 고착된 경우를 복구한다.
     * FOR UPDATE SKIP LOCKED로 조회하므로 다른 인스턴스와 충돌하지 않는다.
     *
     * @param staleThreshold 이 시각보다 이전에 claimed_at이 설정된 row가 복구 대상
     * @param limit          한 번에 복구할 최대 건수
     */
    @Transactional
    public void recoverStaleProcessing(ZonedDateTime staleThreshold, int limit) {
        List<Long> staleIds = outboxEventRepository.findStaleProcessingIds(staleThreshold, limit);
        if (staleIds.isEmpty()) {
            return;
        }
        List<OutboxEvent> staleEvents = outboxEventRepository.findAllById(staleIds);
        for (OutboxEvent event : staleEvents) {
            log.warn("Stale PROCESSING outbox event detected, resetting to PENDING. id={}, claimedAt={}",
                    event.getId(), event.getClaimedAt());
            event.resetStaledProcessingToPending();
        }
        outboxEventRepository.saveAll(staleEvents);
    }
}
