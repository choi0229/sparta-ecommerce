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

/**
 * Outbox 상태 전이(SENT / 재시도 예약)를 담당하는 Bean.
 *
 * <p>Publisher는 claim 트랜잭션을 커밋한 뒤 트랜잭션 밖에서 Kafka로 전송하고, 결과를 건마다 별도 트랜잭션
 * (REQUIRES_NEW)으로 기록한다. 그래서 같은 배치의 다른 이벤트 처리가 실패해도 이미 기록한 SENT나
 * 재시도 횟수가 롤백되지 않는다. 행 잠금을 잡은 바깥 트랜잭션이 없으므로 자기 잠금 대기도 생기지 않는다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxStatusUpdater {

    private static final int MAX_RETRY = 5;
    private static final Duration BASE_BACKOFF = Duration.ofMinutes(1);

    private final OutboxEventRepository outboxEventRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(Long id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));
        event.markSent();
        outboxEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));
        event.markFailedAndScheduleRetry(MAX_RETRY, BASE_BACKOFF);
        outboxEventRepository.save(event);
    }
}
