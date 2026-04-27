package org.teamsparta.logisticsapi.domain.logistics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.logisticsapi.domain.logistics.scheduler.StaleOutboxRecoveryJob;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class StaleOutboxRecoveryJobTest {

    @Mock OutboxEventTransactionalService outboxEventTransactionalService;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @InjectMocks StaleOutboxRecoveryJob job;

    @Test
    @DisplayName("stale 이벤트 없으면 recoverStaleProcessing이 1회 호출된다")
    void noStaleEvents_callsRecoverOnce() {
        given(outboxEventTransactionalService.recoverStaleProcessing(any())).willReturn(0);

        job.recover();

        then(outboxEventTransactionalService).should(times(1)).recoverStaleProcessing(any());
    }

    @Test
    @DisplayName("stale 이벤트가 있으면 recoverStaleProcessing이 1회 호출된다")
    void staleEventsExist_callsRecoverOnce() {
        given(outboxEventTransactionalService.recoverStaleProcessing(any())).willReturn(3);

        job.recover();

        then(outboxEventTransactionalService).should(times(1)).recoverStaleProcessing(any());
    }
}
