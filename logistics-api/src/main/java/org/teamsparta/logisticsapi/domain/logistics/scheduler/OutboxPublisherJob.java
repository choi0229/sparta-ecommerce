package org.teamsparta.logisticsapi.domain.logistics.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherJob {

    private final OutboxEventTransactionalService outboxEventTransactionalService;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 500)
    public void publish() {
        List<OutboxEvent> batch = outboxEventTransactionalService.claimBatch(ZonedDateTime.now(), 50);
        for (OutboxEvent event : batch) {
            try {
                String topic = resolveTopicName(event.getEventType());
                kafkaTemplate.send(topic, event.getAggregateId(), event.getPayload())
                        .get(2, TimeUnit.SECONDS);
                outboxEventTransactionalService.markSent(event.getId());
            } catch (Exception e) {
                log.error("Outbox publish failed. id={}, eventType={}", event.getId(), event.getEventType(), e);
                outboxEventTransactionalService.markFailed(event.getId());
            }
        }
    }

    private String resolveTopicName(String eventType) {
        return switch (eventType) {
            case "shipment-created-event", "shipment-status-changed-event" -> "shipment-event";
            default -> throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        };
    }
}
