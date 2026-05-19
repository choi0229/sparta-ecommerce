package org.teamsparta.logisticsapi.domain.logistics.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.exception.NonRetryableOutboxException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class OutboxPublisherJob {

    private final OutboxEventTransactionalService outboxEventTransactionalService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishSentCounter;
    private final Counter publishFailedCounter;

    public OutboxPublisherJob(OutboxEventTransactionalService outboxEventTransactionalService,
                              KafkaTemplate<String, String> kafkaTemplate,
                              MeterRegistry meterRegistry) {
        this.outboxEventTransactionalService = outboxEventTransactionalService;
        this.kafkaTemplate = kafkaTemplate;
        this.publishSentCounter = Counter.builder("logistics.outbox.publish")
                .tag("result", "sent").register(meterRegistry);
        this.publishFailedCounter = Counter.builder("logistics.outbox.publish")
                .tag("result", "failed").register(meterRegistry);
    }

    public void publish() {
        List<OutboxEvent> batch = outboxEventTransactionalService.claimBatch(ZonedDateTime.now(), 50);
        for (OutboxEvent event : batch) {
            try {
                String topic = resolveTopicName(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                        .get(2, TimeUnit.SECONDS);
                outboxEventTransactionalService.markSent(event.getId());
                publishSentCounter.increment();
            } catch (NonRetryableOutboxException e) {
                log.error("Outbox permanent failure (non-retryable). id={} eventType={}", event.getId(), event.getEventType(), e);
                outboxEventTransactionalService.markPermanentFailed(event.getId());
                publishFailedCounter.increment();
            } catch (Exception e) {
                log.error("Outbox publish failed (retryable). id={} eventType={}", event.getId(), event.getEventType(), e);
                outboxEventTransactionalService.markFailed(event.getId());
                publishFailedCounter.increment();
            }
        }
    }

    private String resolveTopicName(String eventType) {
        return switch (eventType) {
            case "shipment-created-event", "shipment-status-changed-event" -> "shipment-event";
            default -> throw new NonRetryableOutboxException("Unknown eventType: " + eventType);
        };
    }
}
