package org.teamsparta.orderapi.domain.payment.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final String SUCCEEDED_TOPIC = "payment-succeeded-event";
    private final String FAILED_TOPIC = "payment-failed-event";

    public void publisherPaymentSucceeded(PaymentSucceededEvent event){
        log.info("Kafka 메시지 전송 시도: {}", event.orderId());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(SUCCEEDED_TOPIC, jsonEvent);

            future.whenComplete((result,ex)->{
                if(ex!=null){
                    log.error("Failed to send message to topic: {}", SUCCEEDED_TOPIC, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        SUCCEEDED_TOPIC,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.orderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }

    public void publisherPaymentFailed(PaymentFailedEvent event){
        log.info("Kafka 메시지 전송 시도: {}", event.orderId());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(FAILED_TOPIC, jsonEvent);

            future.whenComplete((result,ex)->{
                if(ex!=null){
                    log.error("Failed to send message to topic: {}", FAILED_TOPIC, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        FAILED_TOPIC,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.orderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }
}
