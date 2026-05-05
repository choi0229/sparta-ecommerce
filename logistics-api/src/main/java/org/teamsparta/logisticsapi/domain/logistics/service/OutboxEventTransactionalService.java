package org.teamsparta.logisticsapi.domain.logistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxQueryRepository;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import org.springframework.data.domain.PageRequest;

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
    // claim 만료 시각(now+2분)을 next_retry_at에 기록해 stale recovery 기준으로 활용한다.
    @Transactional
    public List<OutboxEvent> claimBatch(ZonedDateTime now, int batchSize) {
        List<Long> ids = outboxQueryRepository.claimIds(now, now.plusMinutes(2), batchSize);
        if (ids.isEmpty()) return List.of();
        return outboxEventRepository.findAllById(ids);
    }

    private static final int RECOVERY_BATCH_SIZE = 100;

    @Transactional
    public int recoverStaleProcessing(ZonedDateTime now) {
        return outboxQueryRepository.recoverStale(now, now.plusSeconds(30), RECOVERY_BATCH_SIZE);
    }

    @Transactional(readOnly = true)
    public int countStaleHighRetry(ZonedDateTime now, int retryThreshold) {
        return outboxQueryRepository.countStaleHighRetry(now, retryThreshold);
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
        if (event.isFailed()) {
            log.warn("[OutboxTerminal] event reached FAILED (terminal). id={} eventType={} aggregateId={} retryCount={}",
                    id, event.getEventType(), event.getAggregateId(), event.getRetryCount());
        }
    }

    private static final int MAX_QUERY_LIMIT = 200;

    private static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_QUERY_LIMIT));
    }

    @Transactional(readOnly = true)
    public List<OutboxEvent> findByStatus(OutboxStatus status, int limit) {
        return outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                status, PageRequest.of(0, clampLimit(limit)));
    }

    @Transactional
    public OutboxEvent retryFailed(Long id, ZonedDateTime now) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));
        if (!event.isFailed()) {
            throw new DomainException(DomainExceptionCode.OUTBOX_EVENT_NOT_FAILED);
        }
        event.resetForRetry(now);
        log.info("Outbox event manually queued for retry. id={}, retryCount={}", id, event.getRetryCount());
        return outboxEventRepository.save(event);
    }

    @Transactional
    public List<OutboxEvent> retryFailedBatch(OutboxStatus status, int limit, ZonedDateTime now) {
        if (!OutboxStatus.FAILED.equals(status)) {
            throw new DomainException(DomainExceptionCode.OUTBOX_EVENT_NOT_FAILED);
        }
        List<OutboxEvent> events = outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                OutboxStatus.FAILED, PageRequest.of(0, clampLimit(limit)));
        if (events.isEmpty()) {
            return List.of();
        }
        events.forEach(e -> e.resetForRetry(now));
        List<OutboxEvent> saved = outboxEventRepository.saveAll(events);
        log.info("Outbox batch retry queued. count={}", saved.size());
        return saved;
    }
}
