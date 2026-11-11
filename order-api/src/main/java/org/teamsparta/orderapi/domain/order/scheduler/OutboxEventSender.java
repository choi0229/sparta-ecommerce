package org.teamsparta.orderapi.domain.order.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.concurrent.TimeUnit;

/**
 * Outbox 이벤트 한 건을 Kafka로 전송한다. 폴러(OutboxPublisherJob)와 즉시 발행(OutboxImmediatePublisher)이 함께 쓴다.
 * 토픽은 eventType과 같은 이름이고, 메시지 키는 aggregateId다. DB 트랜잭션 밖에서 호출해야 한다.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventSender {

    private static final long SEND_TIMEOUT_SECONDS = 2;

    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 전송이 확인될 때까지(최대 2초) 기다린다. 실패하면 예외를 던진다. */
    public void send(OutboxEvent event) throws Exception {
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
                .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
