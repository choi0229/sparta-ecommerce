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

    private final String SUCCESS_TOPIC = "inventory-reserved-event";
    private final String FAILED_TOPIC = "inventory-failed-event";
    private final String CONFIRM_TOPIC = "inventory-confirm-event";

    public void publisherInventoryReserved(InventoryReservedEvent event) {
        log.info("Kafka 메시지 전송 시도: {}", event.getOrderId());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(SUCCESS_TOPIC, jsonEvent);

            future.whenComplete((result, ex) -> {
                if(ex != null){
                    log.error("Failed to send message to topic: {}", SUCCESS_TOPIC, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        SUCCESS_TOPIC, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.getOrderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }

    public void publisherInventoryReservedFailed(InventoryReserveFailedEvent event) {
        log.info("Kafka 메시지 전송 시도: {}", event.getOrderId());

        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(FAILED_TOPIC, jsonEvent);

            future.whenComplete((result, ex) -> {
                if(ex != null){
                    log.error("Failed to send message to topic: {}", FAILED_TOPIC, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        FAILED_TOPIC, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.getOrderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }

    public void publishInventoryConfirmed(InventoryConfirmedEvent event){
        log.info("Kafka 메시지 전송 시도: {}", event.getOrderId());
        try{
            String jsonEvent = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(CONFIRM_TOPIC, jsonEvent);

            future.whenComplete((result, ex) -> {
                if(ex != null){
                    log.error("Failed to send message to topic: {}", CONFIRM_TOPIC, ex);
                    return;
                }
                log.info("Message sent successfully. topic={}, partition={}, offset={}",
                        CONFIRM_TOPIC, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            });
        }catch(Exception e){
            log.error("JSON 직렬화 중 오류 발생: SKU={}", event.getOrderId(), e);
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }
}
