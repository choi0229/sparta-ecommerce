package org.teamsparta.orderapi.domain.payment.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;

import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentEventPublisher paymentEventPublisher;
    private final OutboxEventRepository outboxEventRepository;

    @KafkaListener(topics = "payment-request-event", groupId = "${spring.application.name}")
    @Transactional
    public void onPaymentRequested(String message) throws JsonProcessingException {
        log.info("Received payment-request-event message: {}", message);
        PaymentRequestedEvent paymentRequestedEvent = objectMapper.readValue(message, PaymentRequestedEvent.class);
        // 가상지연
        // Thread.sleep(ThreadLocalRandom.current().nextInt(200, 801));
        boolean ok = ThreadLocalRandom.current().nextInt(100) < 85; // 85% 성공
        ok = true;

        String eventType;
        String payload;
        if(ok){
            eventType = "payment-succeeded-event";
            payload = objectMapper.writeValueAsString(PaymentSucceededEvent.from(paymentRequestedEvent));
        }else{
            eventType = "payment-failed-event";
            payload = objectMapper.writeValueAsString(PaymentFailedEvent.from(paymentRequestedEvent, "DECLINED"));
        }
        outboxEventRepository.save(OutboxEvent.pending("Payment", paymentRequestedEvent.getOrderId().toString(), eventType, payload));
    }
}
