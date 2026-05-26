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

    // retry_count가 이 값 이상이면 일시 stale이 아닌 지속 실패로 판단해 별도 경고를 남긴다.
    static final int HIGH_RETRY_THRESHOLD = 3;

    private final OutboxEventTransactionalService outboxEventTransactionalService;
    private final Counter staleRecoveredCounter;
    private final Counter staleHighRetryCounter;

    public StaleOutboxRecoveryJob(OutboxEventTransactionalService outboxEventTransactionalService,
                                  MeterRegistry meterRegistry) {
        this.outboxEventTransactionalService = outboxEventTransactionalService;
        this.staleRecoveredCounter = Counter.builder("logistics.outbox.stale.recovered")
                .description("stale PROCESSING events reset to PENDING")
                .register(meterRegistry);
        this.staleHighRetryCounter = Counter.builder("logistics.outbox.stale.high_retry")
                .description("stale PROCESSING events with retryCount >= " + HIGH_RETRY_THRESHOLD)
                .register(meterRegistry);
    }

    public void recover() {
        ZonedDateTime now = ZonedDateTime.now();

        int highRetryCount = outboxEventTransactionalService.countStaleHighRetry(now, HIGH_RETRY_THRESHOLD);
        int recovered = outboxEventTransactionalService.recoverStaleProcessing(now);

        if (recovered > 0) {
            log.warn("[OutboxRecovery] recovered={} PROCESSING→PENDING, highRetry(>={})={}",
                    recovered, HIGH_RETRY_THRESHOLD, highRetryCount);
            staleRecoveredCounter.increment(recovered);

            if (highRetryCount > 0) {
                log.warn("[OutboxRecovery] HIGH-RETRY events detected: count={} retryThreshold={}"
                                + " — possible persistent Kafka connectivity or consumer issue",
                        highRetryCount, HIGH_RETRY_THRESHOLD);
                staleHighRetryCounter.increment(highRetryCount);
            }
        }
    }
}
