package org.teamsparta.logisticsapi.domain.logistics.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scheduler.stale-recovery.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class StaleOutboxRecoveryScheduler {

    private final StaleOutboxRecoveryJob staleOutboxRecoveryJob;

    @Scheduled(fixedDelay = 60_000)
    public void run() {
        staleOutboxRecoveryJob.recover();
    }
}
