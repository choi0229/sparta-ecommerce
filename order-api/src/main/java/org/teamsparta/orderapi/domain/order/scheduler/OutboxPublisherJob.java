package org.teamsparta.orderapi.domain.order.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.repository.OutboxQueryRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class OutboxPublisherJob {

    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxStatusUpdater outboxStatusUpdater;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishSentCounter;
    private final Counter publishFailedCounter;

    public OutboxPublisherJob(OutboxQueryRepository outboxQueryRepository,
                              OutboxStatusUpdater outboxStatusUpdater,
                              KafkaTemplate<String, String> kafkaTemplate,
                              MeterRegistry meterRegistry) {
        this.outboxQueryRepository = outboxQueryRepository;
        this.outboxStatusUpdater = outboxStatusUpdater;
        this.kafkaTemplate = kafkaTemplate;
        this.publishSentCounter = Counter.builder("order.outbox.publish")
                .tag("result", "sent").register(meterRegistry);
        this.publishFailedCounter = Counter.builder("order.outbox.publish")
                .tag("result", "failed").register(meterRegistry);
    }

    @Transactional
    public void publish() {
        List<OutboxEvent> batch = outboxQueryRepository.findBatchForPublish(ZonedDateTime.now(), 50);
        for (OutboxEvent event : batch) {
            try {
                String topicName = switch (event.getEventType()) {
                    case "order-create-event" -> "order-create-event";
                    case "payment-request-event" -> "payment-request-event";
                    case "payment-succeeded-event" -> "payment-succeeded-event";
                    case "payment-failed-event" -> "payment-failed-event";
                    case "order-confirm-event" -> "order-confirm-event";
                    case "productSnapshot-requested-event" -> "productSnapshot-requested-event";
                    default -> throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
                };
                kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                        .get(2, TimeUnit.SECONDS);
                outboxStatusUpdater.markSent(event.getId());
                publishSentCounter.increment();
            } catch (Exception e) {
                log.error("Outbox publish failed. id={}, eventType={}", event.getId(), event.getEventType(), e);
                outboxStatusUpdater.markFailed(event.getId());
                publishFailedCounter.increment();
            }
        }
    }
}
