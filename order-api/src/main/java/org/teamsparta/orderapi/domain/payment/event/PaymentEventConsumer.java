package org.teamsparta.orderapi.domain.payment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentEventPublisher paymentEventPublisher;

    @KafkaListener(topics = "payment-request-event", groupId = "${spring.application.name}")
    public void onPaymentRequested(String message) {
        log.info("Received inventory-reserved-event message: {}", message);
        try{
            PaymentRequestedEvent paymentRequestedEvent = objectMapper.readValue(message, PaymentRequestedEvent.class);
            // 가상지연
            // Thread.sleep(ThreadLocalRandom.current().nextInt(200, 801));
            boolean ok = ThreadLocalRandom.current().nextInt(100) < 85; // 85% 성공
            ok = true;
            if(ok){
                paymentEventPublisher.publisherPaymentSucceeded(PaymentSucceededEvent.from(paymentRequestedEvent));
            }else{
                paymentEventPublisher.publisherPaymentFailed(PaymentFailedEvent.from(paymentRequestedEvent, "DECLINED"));
            }
        }catch(Exception e){
            log.error("Error parsing payment-request-event message: {}", message, e);
        }
    }
}
