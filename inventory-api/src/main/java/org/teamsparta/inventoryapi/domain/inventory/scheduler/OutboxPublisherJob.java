package org.teamsparta.inventoryapi.domain.inventory.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxQueryRepository;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherJob {

    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publish() {
        List<OutboxEvent> batch = outboxQueryRepository.findBatchForPublish(ZonedDateTime.now(), 50);
        for (OutboxEvent event : batch) {
            try{
                String topicName = switch(event.getEventType()){
                    case "inventory-reserved-event" -> "inventory-reserved-event";
                    case "inventory-failed-event" -> "inventory-failed-event";
                    case "inventory-confirm-event" -> "inventory-confirm-event";
                    case "inventory-created-event" -> "inventory-created-event";
                    default -> throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
                };
                kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                        .get(2, TimeUnit.SECONDS);
                updateToSent(event.getId());
            }catch (Exception e){
                log.error("Outbox publish failed. id={}, eventType={}", event.getId(), event.getEventType(), e);
                // 4. 실패 및 재시도 처리
                handleFailure(event.getId(), e.getMessage());
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateToSent(Long id) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));
        event.markSent();
        outboxEventRepository.save(event);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailure(Long id, String error) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));

        // 최대 재시도 5회, 다음 재시도까지 1분 지연
        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
    }
}
