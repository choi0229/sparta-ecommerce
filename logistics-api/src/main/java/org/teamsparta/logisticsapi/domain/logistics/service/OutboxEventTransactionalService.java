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

    // 네이티브 UPDATE...RETURNING으로 PENDING → PROCESSING 전이를 원자적으로 수행한다.
    // claimIds 트랜잭션이 커밋되는 시점에 FOR UPDATE SKIP LOCKED 잠금이 해제되므로
    // 이후 markSent/markFailed(REQUIRES_NEW)는 잠금 없이 id 기준 UPDATE만 수행한다.
    @Transactional
    public List<OutboxEvent> claimBatch(ZonedDateTime now, int batchSize) {
        List<Long> ids = outboxQueryRepository.claimIds(now, batchSize);
        if (ids.isEmpty()) return List.of();
        return outboxEventRepository.findAllById(ids);
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
