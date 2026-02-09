package org.teamsparta.orderapi.domain.order.event;

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
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String topicName = "order-create-event";
    private final ObjectMapper objectMapper;

    public void publisherOrderCreated(OrderCreatedEvent event){
        log.info("Kafka 메시지 전송 시도: {}", event.getOrderId());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topicName, jsonEvent);

            future.whenComplete((result,ex)->{
                if(ex!=null){
                    log.error("Failed to send message to topic: {}", topicName, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        topicName,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.getOrderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }


    }
}
