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
import org.teamsparta.orderapi.domain.payment.repository.PaymentMockResultRepository;
import org.teamsparta.orderapi.domain.payment.service.MockPaymentJudge;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final ObjectMapper objectMapper;
    private final PaymentEventPublisher paymentEventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentMockResultRepository paymentMockResultRepository;
    private final MockPaymentJudge mockPaymentJudge;

    private static final String DECLINED = "DECLINED";

    /**
     * 실제 PG 연동이 아닌 Mock 결제. 주문당 한 번만 판정하고, 판정 기록과 결과 Outbox를 같은 트랜잭션에 저장한다.
     * 중복 이벤트는 기존 결과를 그대로 두며, 결과 이벤트는 처음 판정할 때 이미 Outbox에 저장되어 있다.
     * JsonProcessingException은 체크 예외라 기본 설정으로는 롤백되지 않으므로 rollbackFor를 지정한다.
     */
    @KafkaListener(topics = "payment-request-event", groupId = "${spring.application.name}")
    @Transactional(rollbackFor = Exception.class)
    public void onPaymentRequested(String message) throws JsonProcessingException {
        log.info("Received payment-request-event message: {}", message);
        PaymentRequestedEvent paymentRequestedEvent = objectMapper.readValue(message, PaymentRequestedEvent.class);
        Long orderId = paymentRequestedEvent.getOrderId();

        if(paymentMockResultRepository.existsById(orderId)){
            log.info("Duplicate payment-request-event ignored. orderId={}", orderId);
            return;
        }

        boolean ok = mockPaymentJudge.approve();
        String failureReason = ok ? null : DECLINED;
        if(paymentMockResultRepository.insertIfAbsent(orderId, paymentRequestedEvent.getAmount(), ok, failureReason) == 0){
            // 동시에 처리한 쪽이 먼저 저장했다. 그 결과를 유지하고 이 판정은 버린다.
            log.info("Duplicate payment-request-event ignored after concurrent processing. orderId={}", orderId);
            return;
        }

        String eventType;
        String payload;
        if(ok){
            eventType = "payment-succeeded-event";
            payload = objectMapper.writeValueAsString(PaymentSucceededEvent.from(paymentRequestedEvent));
        }else{
            eventType = "payment-failed-event";
            payload = objectMapper.writeValueAsString(PaymentFailedEvent.from(paymentRequestedEvent, failureReason));
        }
        outboxEventRepository.save(OutboxEvent.pending("Payment", orderId.toString(), eventType, payload));
    }
}
