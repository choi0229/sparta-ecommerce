package org.teamsparta.logisticsapi.domain.logistics.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;

import java.time.ZonedDateTime;

@Component
@Slf4j
public class StaleOutboxRecoveryJob {

    private final OutboxEventTransactionalService outboxEventTransactionalService;
    private final Counter staleRecoveredCounter;

    public StaleOutboxRecoveryJob(OutboxEventTransactionalService outboxEventTransactionalService,
                                  MeterRegistry meterRegistry) {
        this.outboxEventTransactionalService = outboxEventTransactionalService;
        this.staleRecoveredCounter = Counter.builder("logistics.outbox.stale.recovered")
                .register(meterRegistry);
    }

    public void recover() {
        int recovered = outboxEventTransactionalService.recoverStaleProcessing(ZonedDateTime.now());
        if (recovered > 0) {
            log.warn("Stale outbox recovery: {} PROCESSING event(s) reset to PENDING", recovered);
            staleRecoveredCounter.increment(recovered);
        }
    }
}
