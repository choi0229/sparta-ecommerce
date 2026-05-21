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
 * Outbox 상태 전이(SENT / FAILED)를 담당하는 Bean.
 *
 * <p>OutboxPublisherJob과 분리된 이유:
 * Spring AOP 프록시는 같은 Bean 내부에서 this.method()로 호출되는 경우 트랜잭션 어노테이션을
 * 적용하지 않는다. 이 Bean으로 분리하면 REQUIRES_NEW 트랜잭션이 실제로 동작한다.
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
