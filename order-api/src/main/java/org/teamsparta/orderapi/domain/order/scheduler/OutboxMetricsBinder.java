package org.teamsparta.orderapi.domain.order.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

@Component
@RequiredArgsConstructor
public class OutboxMetricsBinder implements MeterBinder {

    private final OutboxEventRepository outboxEventRepository;

    @Override
    public void bindTo(MeterRegistry registry) {
        for (OutboxStatus status : OutboxStatus.values()) {
            Gauge.builder("order.outbox.events", outboxEventRepository,
                            r -> r.countByStatus(status))
                    .tag("status", status.name())
                    .description("outbox_event row count by status")
                    .register(registry);
        }
    }
}
