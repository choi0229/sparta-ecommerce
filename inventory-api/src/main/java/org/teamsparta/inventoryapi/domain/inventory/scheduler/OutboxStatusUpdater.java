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
