package org.teamsparta.productapi.domain.product.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxQueryRepository;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherJob {

    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxStatusUpdater outboxStatusUpdater;   // 별도 Bean — REQUIRES_NEW 정상 적용
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void publish() {
        List<OutboxEvent> batch = outboxQueryRepository.findBatchForPublish(ZonedDateTime.now(), 50);
        for (OutboxEvent event : batch) {

            // ── Block 1: topic resolve + Kafka send ─────────────────────────
            // 실패 시 markFailed 후 다음 이벤트로 continue
            try {
                String topicName = switch (event.getEventType()) {
                    case "variant-created-event"        -> "variant-created-event";
                    case "product-variant-event"        -> "product-variant-event";
                    case "productSnapshot-reply-event"  -> "productSnapshot-reply-event";
                    case "inventory-init-event"         -> "inventory-init-event";
                    default -> throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
                };
                kafkaTemplate.send(topicName, event.getAggregateId(), event.getPayload())
                        .get(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 스레드 인터럽트 플래그 복원 — markFailed 결과에 무관하게 반드시 복원
                Thread.currentThread().interrupt();
                log.error("Outbox publish interrupted. id={}, eventType={}", event.getId(), event.getEventType(), e);
                try {
                    outboxStatusUpdater.markFailed(event.getId());
                } catch (Exception markEx) {
                    log.warn("markFailed also failed during interrupt handling. id={}", event.getId(), markEx);
                }
                break; // 인터럽트 발생 시 루프 중단
            } catch (Exception e) {
                log.error("Outbox publish failed. id={}, eventType={}", event.getId(), event.getEventType(), e);
                try {
                    outboxStatusUpdater.markFailed(event.getId());
                } catch (Exception markEx) {
                    log.warn("markFailed failed for id={}. Event will be retried by stale recovery.", event.getId(), markEx);
                }
                continue; // Kafka 실패 → markFailed 성공 여부와 무관하게 다음 이벤트 처리
            }

            // ── Block 2: Kafka 전송 성공 → markSent ─────────────────────────
            // markFailed() 호출 금지: Kafka 전송은 이미 성공했으므로 재발행 유발 금지
            try {
                outboxStatusUpdater.markSent(event.getId());
            } catch (Exception e) {
                log.error("markSent failed after successful Kafka publish. id={}. " +
                        "DB may be unstable; stopping loop for stale recovery.", event.getId(), e);
                break; // DB 불안정 → 루프 중단, stale recovery에서 재처리
            }
        }
    }
}
