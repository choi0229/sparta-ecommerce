package org.teamsparta.orderapi.domain.order.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Outbox 이벤트를 Kafka로 발행한다. (inventory-api와 같은 claim 구조)
 *
 * <ol>
 *   <li>Stale PROCESSING 복구: SENT 처리 전에 중단된 PROCESSING → PENDING (별도 트랜잭션)</li>
 *   <li>PENDING claim: FOR UPDATE SKIP LOCKED로 잠근 행을 PROCESSING으로 바꾸고 커밋 (짧은 트랜잭션)</li>
 *   <li>Kafka send: 트랜잭션 밖에서 수행 — 전송 중에는 Outbox 행 잠금이 없다</li>
 *   <li>상태 전이: 성공 시 SENT, 실패 시 PENDING(재시도 예약) 또는 FAILED — 건마다 별도 트랜잭션</li>
 * </ol>
 *
 * <p>@Transactional 없음: 각 단계는 OutboxEventClaimer·OutboxStatusUpdater의 개별 트랜잭션으로 처리된다.
 *
 * <p>outbox.immediate-publish.enabled=true면 대부분의 행은 커밋 직후 OutboxImmediatePublisher가 발행하고,
 * 이 폴러는 즉시 발행이 실패(재시도 예약)했거나 건너뛴 행, stale PROCESSING을 처리하는 안전망이 된다.
 */
@Component
@Slf4j
public class OutboxPublisherJob {

    private final OutboxEventClaimer outboxEventClaimer;
    private final OutboxStatusUpdater outboxStatusUpdater;
    private final OutboxEventSender outboxEventSender;
    private final Counter publishSentCounter;
    private final Counter publishFailedCounter;
    private final int batchSize;
    private final Duration staleProcessingTimeout;

    public OutboxPublisherJob(OutboxEventClaimer outboxEventClaimer,
                              OutboxStatusUpdater outboxStatusUpdater,
                              OutboxEventSender outboxEventSender,
                              MeterRegistry meterRegistry,
                              @Value("${scheduler.outbox.batch-size:50}") int batchSize,
                              @Value("${scheduler.outbox.stale-processing-timeout-ms:300000}") long staleProcessingTimeoutMs) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("scheduler.outbox.batch-size must be at least 1: " + batchSize);
        }
        if (staleProcessingTimeoutMs < 1) {
            throw new IllegalArgumentException(
                    "scheduler.outbox.stale-processing-timeout-ms must be at least 1: " + staleProcessingTimeoutMs);
        }
        this.outboxEventClaimer = outboxEventClaimer;
        this.outboxStatusUpdater = outboxStatusUpdater;
        this.outboxEventSender = outboxEventSender;
        this.publishSentCounter = Counter.builder("order.outbox.publish")
                .tag("result", "sent").register(meterRegistry);
        this.publishFailedCounter = Counter.builder("order.outbox.publish")
                .tag("result", "failed").register(meterRegistry);
        this.batchSize = batchSize;
        this.staleProcessingTimeout = Duration.ofMillis(staleProcessingTimeoutMs);
    }

    public void publish() {
        ZonedDateTime now = ZonedDateTime.now();

        // 1. SENT 처리 전에 중단된 stale PROCESSING 이벤트를 PENDING으로 복구
        outboxEventClaimer.recoverStaleProcessing(now.minus(staleProcessingTimeout), batchSize);

        // 2. PENDING → PROCESSING claim (짧은 트랜잭션, Kafka 호출 없음)
        List<OutboxEvent> batch = outboxEventClaimer.claimBatch(now, batchSize);

        // 3. Kafka send (DB 트랜잭션 밖에서 수행)
        for (OutboxEvent event : batch) {
            try {
                outboxEventSender.send(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Outbox publish interrupted. id={}, eventType={}", event.getId(), event.getEventType(), e);
                outboxStatusUpdater.markFailed(event.getId());
                publishFailedCounter.increment();
                break; // interrupt 플래그 복원 후 루프 종료 — 남은 PROCESSING은 stale 복구가 되돌린다
            } catch (Exception e) {
                log.error("Outbox publish failed. id={}, eventType={}", event.getId(), event.getEventType(), e);
                outboxStatusUpdater.markFailed(event.getId());
                publishFailedCounter.increment();
                continue;
            }

            // Kafka 전송 성공 후 상태 전이 — 실패해도 markFailed()를 부르지 않는다
            // (이미 발행된 이벤트를 재시도 대상으로 되돌리면 중복 발행된다)
            try {
                outboxStatusUpdater.markSent(event.getId());
                publishSentCounter.increment();
            } catch (Exception e) {
                log.error("Status update failed after successful Kafka publish. id={}, eventType={}",
                        event.getId(), event.getEventType(), e);
                publishFailedCounter.increment();
                break; // DB 갱신 불안정 — 남은 배치 중단 (stale 복구가 다시 발행한다)
            }
        }
    }
}
