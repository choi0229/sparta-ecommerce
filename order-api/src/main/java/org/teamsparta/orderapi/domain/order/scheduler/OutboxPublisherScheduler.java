package org.teamsparta.orderapi.domain.order.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "scheduler.outbox.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class OutboxPublisherScheduler {

    private final OutboxPublisherJob outboxPublisherJob;

    // 실행 주기: scheduler.outbox.fixed-delay-ms (이전 실행이 끝난 뒤 기다리는 시간, 기본 1000ms — 즉시 발행의 안전망)
    @Scheduled(fixedDelayString = "${scheduler.outbox.fixed-delay-ms:1000}")
    public void run() {
        outboxPublisherJob.publish();
    }
}
