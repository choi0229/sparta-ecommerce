package org.teamsparta.inventoryapi.domain.inventory.scheduler;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 커밋 직후 발행: Outbox 행을 저장한 트랜잭션이 커밋되면 Publisher 주기를 기다리지 않고 그 행을 바로 발행한다.
 * 폴러(OutboxPublisherJob)는 즉시 발행이 실패하거나 건너뛴 행을 처리하는 안전망으로 남는다.
 *
 * <ol>
 *   <li>AFTER_COMMIT에서 행 ID를 별도 실행기에 넘긴다. 커밋한 스레드(HTTP 요청, Kafka Consumer)는 기다리지 않는다.
 *       롤백되면 이 리스너가 호출되지 않는다.</li>
 *   <li>실행기 큐가 가득 차면 기다리지 않고 건너뛴다(skipped). 행은 PENDING으로 남아 폴러가 발행한다.</li>
 *   <li>실행기 스레드에서 새 트랜잭션으로 조건부 claim한다(PENDING일 때만 PROCESSING). 폴러가 먼저 가져갔으면
 *       갱신 행이 0건이므로 아무것도 하지 않는다(missed).</li>
 *   <li>트랜잭션 밖에서 Kafka로 전송하고, 성공하면 SENT(자신의 claim인 행에만), 실패하면 폴러와 같은 재시도 예약을
 *       별도 트랜잭션으로 기록한다.</li>
 * </ol>
 *
 * <p>outbox.immediate-publish.enabled가 true(기본값)일 때 등록된다. false면 폴링만으로 발행한다
 * (이때는 scheduler.outbox.fixed-delay-ms도 100ms로 함께 줄인다 — README "Outbox 발행 기본값과 되돌리기").
 * 전달 보장은 폴러와 같은 At-Least-Once다(전송 성공 후 SENT 기록 전에 중단되면 stale 복구 후 다시 발행된다).
 */
@Component
@ConditionalOnProperty(name = "outbox.immediate-publish.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxImmediatePublisher implements DisposableBean {

    private final OutboxEventClaimer outboxEventClaimer;
    private final OutboxStatusUpdater outboxStatusUpdater;
    private final OutboxEventSender outboxEventSender;
    private final ThreadPoolExecutor executor;
    private final Counter sentCounter;
    private final Counter failedCounter;
    private final Counter skippedCounter;
    private final Counter missedCounter;

    public OutboxImmediatePublisher(OutboxEventClaimer outboxEventClaimer,
                                    OutboxStatusUpdater outboxStatusUpdater,
                                    OutboxEventSender outboxEventSender,
                                    MeterRegistry meterRegistry,
                                    @Value("${outbox.immediate-publish.threads:4}") int threads,
                                    @Value("${outbox.immediate-publish.queue-capacity:1000}") int queueCapacity) {
        if (threads < 1) {
            throw new IllegalArgumentException("outbox.immediate-publish.threads must be at least 1: " + threads);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException(
                    "outbox.immediate-publish.queue-capacity must be at least 1: " + queueCapacity);
        }
        this.outboxEventClaimer = outboxEventClaimer;
        this.outboxStatusUpdater = outboxStatusUpdater;
        this.outboxEventSender = outboxEventSender;
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "outbox-immediate-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.sentCounter = counter(meterRegistry, "sent");
        this.failedCounter = counter(meterRegistry, "failed");
        this.skippedCounter = counter(meterRegistry, "skipped");
        this.missedCounter = counter(meterRegistry, "missed");
        Gauge.builder("inventory.outbox.immediate.queue", executor, e -> e.getQueue().size())
                .description("immediate publish executor queue size").register(meterRegistry);
    }

    private static Counter counter(MeterRegistry registry, String result) {
        return Counter.builder("inventory.outbox.immediate").tag("result", result)
                .description("immediate outbox publish: sent/failed(retry scheduled)/skipped(queue full)/missed(claimed by poller)")
                .register(registry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRecorded(OutboxEventRecorded recorded) {
        Long id = recorded.outboxEventId();
        try {
            executor.execute(() -> publish(id));
        } catch (RejectedExecutionException e) {
            // 큐가 가득 찼거나 종료 중: 기다리지 않는다. 행은 PENDING이므로 폴러가 발행한다
            skippedCounter.increment();
            log.debug("Immediate outbox publish skipped (executor saturated). id={}", id);
        }
    }

    void publish(Long id) {
        Optional<OutboxEvent> claimed;
        try {
            claimed = outboxEventClaimer.claimIfPending(id, ZonedDateTime.now());
        } catch (Exception e) {
            // claim하지 못했으므로 행은 PENDING이다. 폴러가 발행한다
            missedCounter.increment();
            log.warn("Immediate outbox claim failed, leaving it to the poller. id={}", id, e);
            return;
        }
        if (claimed.isEmpty()) {
            missedCounter.increment(); // 폴러가 먼저 가져갔다
            return;
        }
        OutboxEvent event = claimed.get();

        try {
            outboxEventSender.send(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduleRetry(event, e);
            return;
        } catch (Exception e) {
            scheduleRetry(event, e);
            return;
        }

        // 전송 성공 후 상태 전이 — 실패해도 재시도 예약을 하지 않는다(중복 발행 방지). PROCESSING은 stale 복구가 되돌린다
        try {
            if (outboxStatusUpdater.markSent(List.of(event.getId()), event.getClaimedAt()) == 0) {
                // stale 복구 후 다른 쪽이 다시 claim한 행: 그쪽 claim의 기록을 덮어쓰지 않는다
                log.warn("Outbox event was re-claimed before SENT was recorded. id={}", event.getId());
            }
            sentCounter.increment();
        } catch (Exception e) {
            failedCounter.increment();
            log.error("Status update failed after successful Kafka publish. id={}, eventType={}",
                    event.getId(), event.getEventType(), e);
        }
    }

    private void scheduleRetry(OutboxEvent event, Exception cause) {
        failedCounter.increment();
        log.error("Immediate outbox publish failed, retry scheduled. id={}, eventType={}",
                event.getId(), event.getEventType(), cause);
        try {
            outboxStatusUpdater.markFailed(event.getId(), event.getClaimedAt());
        } catch (Exception e) {
            log.error("Retry scheduling failed; stale recovery will reset it. id={}", event.getId(), e);
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        // 남은 작업의 행은 PENDING(또는 claim 직후면 PROCESSING)으로 남고, 다음 기동 후 폴러·stale 복구가 처리한다
        executor.shutdown();
        if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            executor.shutdownNow();
        }
    }
}
