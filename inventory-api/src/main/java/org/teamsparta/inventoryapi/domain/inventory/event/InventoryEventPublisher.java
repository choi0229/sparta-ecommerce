package org.teamsparta.inventoryapi.domain.inventory.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final String topicName = "inventory-reserved-event";

    public void publisherInventoryReserved(InventoryReservedEvent event) {
        log.info("Kafka 메시지 전송 시도: {}", event.getOrderId());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(topicName, jsonEvent);

            future.whenComplete((result, ex) -> {
                if(ex != null){
                    log.error("Failed to send message to topic: {}", topicName, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        topicName, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.getOrderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }
}
