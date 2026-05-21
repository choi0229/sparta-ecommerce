package org.teamsparta.inventoryapi.domain.inventory.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 이벤트를 Kafka로 발행하는 스케줄러.
 *
 * <p>흐름:
 * <ol>
 *   <li>Stale PROCESSING 복구: publisher 크래시로 고착된 PROCESSING → PENDING (별도 트랜잭션)</li>
 *   <li>PENDING claim: PENDING 이벤트를 PROCESSING으로 전이하고 커밋 (짧은 트랜잭션, DB 락 빠른 해제)</li>
 *   <li>Kafka send: 트랜잭션 밖에서 수행 — DB 락을 잡은 채 블로킹하지 않음</li>
 *   <li>상태 전이: 성공 시 SENT, 실패 시 PENDING(재시도) 또는 FAILED(최대 재시도 초과)</li>
 * </ol>
 *
 * <p>중복 발행 방지: PROCESSING claim이 단일 트랜잭션 안에서 PESSIMISTIC_WRITE + 상태 전이를
 * 함께 처리하므로 멀티 인스턴스 환경에서 같은 이벤트가 중복 claim되지 않는다.
 *
 * <p>@Transactional 없음: Kafka send는 DB 트랜잭션 밖에서 수행된다.
 * 각 상태 전이(claimBatch, markSent, markFailed)는 OutboxEventClaimer·OutboxStatusUpdater의
 * 개별 REQUIRES_NEW 트랜잭션으로 처리된다.
 */
@Component
@Slf4j
public class OutboxPublisherJob {

    private static final int BATCH_SIZE = 50;
    private static final long STALE_PROCESSING_TIMEOUT_MINUTES = 5;

    private final OutboxEventClaimer outboxEventClaimer;
    private final OutboxStatusUpdater outboxStatusUpdater;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishSentCounter;
    private final Counter publishFailedCounter;

    public OutboxPublisherJob(OutboxEventClaimer outboxEventClaimer,
                              OutboxStatusUpdater outboxStatusUpdater,
                              KafkaTemplate<String, String> kafkaTemplate,
                              MeterRegistry meterRegistry) {
        this.outboxEventClaimer = outboxEventClaimer;
        this.outboxStatusUpdater = outboxStatusUpdater;
        this.kafkaTemplate = kafkaTemplate;
        this.publishSentCounter = Counter.builder("inventory.outbox.publish")
                .tag("result", "sent").register(meterRegistry);
        this.publishFailedCounter = Counter.builder("inventory.outbox.publish")
                .tag("result", "failed").register(meterRegistry);
    }

    @Scheduled(fixedDelay = 500)
    public void publish() {
        ZonedDateTime now = ZonedDateTime.now();

        // 1. Publisher 크래시로 고착된 stale PROCESSING 이벤트를 PENDING으로 복구
        outboxEventClaimer.recoverStaleProcessing(
                now.minusMinutes(STALE_PROCESSING_TIMEOUT_MINUTES), BATCH_SIZE);

        // 2. PENDING → PROCESSING claim (짧은 트랜잭션, Kafka 호출 없음)
        List<OutboxEvent> batch = outboxEventClaimer.claimBatch(now, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        // 3. Kafka send (DB 트랜잭션 밖에서 수행)
        for (OutboxEvent event : batch) {
            try {
                String topicName = switch (event.getEventType()) {
                    case "inventory-reserved-event" -> "inventory-reserved-event";
                    case "inventory-failed-event" -> "inventory-failed-event";
                    case "inventory-confirm-event" -> "inventory-confirm-event";
                    case "inventory-created-event" -> "inventory-created-event";
                    case "inventory-expired-event" -> "inventory-expired-event";
                    default -> throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
                };
                kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                        .get(2, TimeUnit.SECONDS);
                outboxStatusUpdater.markSent(event.getId());
                publishSentCounter.increment();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Outbox publish interrupted. id={}, eventType={}", event.getId(), event.getEventType(), e);
                outboxStatusUpdater.markFailed(event.getId());
                publishFailedCounter.increment();
                break; // interrupt 플래그 복원 후 루프 종료
            } catch (Exception e) {
                log.error("Outbox publish failed. id={}, eventType={}", event.getId(), event.getEventType(), e);
                outboxStatusUpdater.markFailed(event.getId());
                publishFailedCounter.increment();
            }
        }
    }
}
