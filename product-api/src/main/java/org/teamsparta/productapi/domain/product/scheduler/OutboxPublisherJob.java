package org.teamsparta.productapi.domain.product.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.OutboxQueryRepository;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class OutboxPublisherJob {

    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxPublisherJob(OutboxQueryRepository outboxQueryRepository,
                              OutboxEventRepository outboxEventRepository,
                              KafkaTemplate<String, String> kafkaTemplate,
                              @Value("${scheduler.outbox.batch-size:50}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("scheduler.outbox.batch-size must be at least 1: " + batchSize);
        }
        this.outboxQueryRepository = outboxQueryRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    // 실행 주기: scheduler.outbox.fixed-delay-ms (이전 실행이 끝난 뒤 기다리는 시간, 기본 500ms)
    @Scheduled(fixedDelayString = "${scheduler.outbox.fixed-delay-ms:500}")
    @Transactional
    public void publish() {
        List<OutboxEvent> batch = outboxQueryRepository.findBatchForPublish(ZonedDateTime.now(), batchSize);
        for (OutboxEvent event : batch) {
            try{
                String topicName = switch(event.getEventType()){
                    case "variant-created-event" -> "variant-created-event";
                    case "product-variant-event" -> "product-variant-event";
                    case "productSnapshot-reply-event" -> "productSnapshot-reply-event";
                    case "inventory-init-event" -> "inventory-init-event";
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
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleFailure(Long id, String error) {
        OutboxEvent event = outboxEventRepository.findById(id)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.EVENT_NOT_FOUND));

        // 최대 재시도 5회, 다음 재시도까지 1분 지연
        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
    }
}

