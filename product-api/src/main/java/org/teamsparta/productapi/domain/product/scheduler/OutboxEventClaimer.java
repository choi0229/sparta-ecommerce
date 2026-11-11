package org.teamsparta.productapi.domain.product.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.OutboxQueryRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * PENDING → PROCESSING claim 과 stale PROCESSING → PENDING 복구를 담당한다. (inventory-api와 같은 구조)
 *
 * <p>claimBatch()는 짧은 단일 트랜잭션 안에서 FOR UPDATE SKIP LOCKED 조회 → PROCESSING 전이 → 커밋까지 끝낸다.
 * 다른 Publisher가 잠근 행은 건너뛰고, 커밋 뒤에는 PENDING이 아니므로 다시 claim되지 않는다.
 * Kafka 전송은 이 트랜잭션 밖에서 OutboxPublisherJob이 처리한다.
 *
 * <p>stale PROCESSING recovery: publisher가 SENT 처리 전에 중단되면 PROCESSING이 남는다.
 * claimed_at 기준 timeout 이후 recoverStaleProcessing()이 PENDING으로 되돌려 다시 발행한다(At-Least-Once).
 * 이때 retryCount는 건드리지 않는다(전송 실패가 아닌 publisher 장애이므로).
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
     * @param now   조회 기준 시각 (nextRetryAt 비교에 사용)
     * @param limit 한 번에 claim할 최대 건수
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
     * FOR UPDATE SKIP LOCKED로 조회하므로 다른 인스턴스와 충돌하지 않는다.
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

    /**
     * 즉시 발행용 단건 claim. 새 트랜잭션에서 PENDING일 때만 PROCESSING으로 바꾸고 커밋한다.
     *
     * @return claim에 성공한 이벤트. 폴러가 먼저 가져갔거나 재시도 대기 중이면 empty
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<OutboxEvent> claimIfPending(Long id, ZonedDateTime now) {
        if (outboxEventRepository.claimIfPending(id, now) != 1) {
            return Optional.empty();
        }
        return outboxEventRepository.findById(id);
    }
}
