package org.teamsparta.orderapi.domain.order.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;

/**
 * Outbox 상태 전이(SENT / 재시도 예약)를 담당하는 Bean.
 *
 * <p>Publisher는 claim 트랜잭션을 커밋한 뒤 트랜잭션 밖에서 Kafka로 전송하고, 결과를 별도 트랜잭션
 * (REQUIRES_NEW)으로 기록한다. 실패한 행은 건마다 재시도를 예약하고, 성공한 행은 배치 끝에 한 번의 UPDATE로
 * SENT 처리한다. 그래서 같은 배치의 다른 이벤트 처리가 실패해도 이미 기록한 SENT나 재시도 횟수가 롤백되지 않는다.
 * 행 잠금을 잡은 바깥 트랜잭션이 없으므로 자기 잠금 대기도 생기지 않는다.
 */
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
