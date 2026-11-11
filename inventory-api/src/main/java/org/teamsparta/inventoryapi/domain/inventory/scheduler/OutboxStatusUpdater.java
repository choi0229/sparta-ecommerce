package org.teamsparta.inventoryapi.domain.inventory.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxStatusUpdater {

    private static final int MAX_RETRY = 5;
    private static final Duration BASE_BACKOFF = Duration.ofMinutes(1);

    private final OutboxEventRepository outboxEventRepository;

    /**
     * 전송에 성공한 행들을 한 번의 UPDATE로 SENT 처리한다(SELECT 없음).
     * claimedAt이 같은 PROCESSING 행에만 적용되므로, stale 복구 후 다른 쪽이 다시 claim한 행은 덮어쓰지 않는다.
     *
     * @param ids       전송에 성공한 행 ID (같은 claim에 속한 행들)
     * @param claimedAt 그 행들을 claim할 때 기록한 claimed_at
     * @return SENT로 바뀐 행 수. ids보다 적으면 나머지는 이미 다른 쪽 claim으로 넘어간 행이다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markSent(Collection<Long> ids, ZonedDateTime claimedAt) {
        if (ids.isEmpty()) {
            return 0;
        }
        return outboxEventRepository.markSentIfClaimed(ids, claimedAt, ZonedDateTime.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));
        event.markFailedAndScheduleRetry(MAX_RETRY, BASE_BACKOFF);
        outboxEventRepository.save(event);
    }
}
