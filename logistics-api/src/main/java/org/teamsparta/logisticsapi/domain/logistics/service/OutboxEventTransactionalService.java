package org.teamsparta.logisticsapi.domain.logistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxQueryRepository;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventTransactionalService {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxQueryRepository outboxQueryRepository;

    // 조회 트랜잭션을 publish() 루프와 분리한다.
    // 이 메서드가 반환하면 PESSIMISTIC_WRITE 잠금이 즉시 해제되므로
    // 이후 markSent/markFailed(REQUIRES_NEW)가 같은 행을 lock conflict 없이 UPDATE할 수 있다.
    @Transactional
    public List<OutboxEvent> fetchBatch(ZonedDateTime now, int batchSize) {
        return outboxQueryRepository.findBatchForPublish(now, batchSize);
    }

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
        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        outboxEventRepository.save(event);
    }
}
