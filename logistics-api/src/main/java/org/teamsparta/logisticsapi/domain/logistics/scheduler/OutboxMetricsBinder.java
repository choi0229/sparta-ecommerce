package org.teamsparta.logisticsapi.domain.logistics.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;

@Component
@RequiredArgsConstructor
public class OutboxMetricsBinder implements MeterBinder {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (OutboxStatus status : OutboxStatus.values()) {
            Gauge.builder("logistics.outbox.events", outboxEventRepository,
                            r -> r.countByStatus(status))
                    .tag("status", status.name())
                    .description("outbox_event row count by status")
                    .register(registry);
        }
    }
}
