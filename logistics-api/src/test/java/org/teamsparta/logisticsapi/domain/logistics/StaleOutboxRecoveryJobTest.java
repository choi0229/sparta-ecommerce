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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class StaleOutboxRecoveryJobTest {

    @Mock OutboxEventTransactionalService outboxEventTransactionalService;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
    @InjectMocks StaleOutboxRecoveryJob job;

    @Test
    @DisplayName("stale 이벤트 없으면 recover는 호출되지만 카운터는 증가하지 않는다")
    void noStaleEvents_countersNotIncremented() {
        given(outboxEventTransactionalService.countStaleHighRetry(any(), anyInt())).willReturn(0);
        given(outboxEventTransactionalService.recoverStaleProcessing(any())).willReturn(0);

        job.recover();

        then(outboxEventTransactionalService).should(times(1)).recoverStaleProcessing(any());
        assertThat(meterRegistry.counter("logistics.outbox.stale.recovered").count()).isEqualTo(0.0);
        assertThat(meterRegistry.counter("logistics.outbox.stale.high_retry").count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("stale 이벤트가 있으면 staleRecovered 카운터가 복구 건수만큼 증가한다")
    void staleEventsExist_incrementsRecoveredCounter() {
        given(outboxEventTransactionalService.countStaleHighRetry(any(), anyInt())).willReturn(0);
        given(outboxEventTransactionalService.recoverStaleProcessing(any())).willReturn(3);

        job.recover();

        then(outboxEventTransactionalService).should(times(1)).recoverStaleProcessing(any());
        assertThat(meterRegistry.counter("logistics.outbox.stale.recovered").count()).isEqualTo(3.0);
        assertThat(meterRegistry.counter("logistics.outbox.stale.high_retry").count()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("high-retry stale 이벤트가 있으면 staleHighRetry 카운터가 해당 건수만큼 증가한다")
    void highRetryStaleEventsExist_incrementsHighRetryCounter() {
        given(outboxEventTransactionalService.countStaleHighRetry(any(), anyInt())).willReturn(2);
        given(outboxEventTransactionalService.recoverStaleProcessing(any())).willReturn(5);

        job.recover();

        assertThat(meterRegistry.counter("logistics.outbox.stale.recovered").count()).isEqualTo(5.0);
        assertThat(meterRegistry.counter("logistics.outbox.stale.high_retry").count()).isEqualTo(2.0);
    }
}
