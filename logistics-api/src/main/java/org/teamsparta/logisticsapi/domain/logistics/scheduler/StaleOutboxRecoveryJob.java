package org.teamsparta.logisticsapi.domain.logistics.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;

import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class StaleOutboxRecoveryJob {

    private final OutboxEventTransactionalService outboxEventTransactionalService;

    @Scheduled(fixedDelay = 60_000)
    public void recover() {
        int recovered = outboxEventTransactionalService.recoverStaleProcessing(ZonedDateTime.now());
        if (recovered > 0) {
            log.warn("Stale outbox recovery: {} PROCESSING event(s) reset to PENDING", recovered);
        }
    }
}
