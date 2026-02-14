package org.teamsparta.inventoryapi.domain.inventory.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderConfirmResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.VariantCreatResult;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryService;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventConsumer {

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "order-create-event", groupId = "${spring.application.name}")
    public void orderCreateEvent(String message) {
        log.info("Received order-create-event message: {}", message);
        try{
            OrderCreateResult orderCreateResult = objectMapper.readValue(message, OrderCreateResult.class);
            inventoryService.reserveInventory(orderCreateResult);
        }catch(JsonProcessingException e){
            log.error("Fatal: Invalid JSON format. Message: {}", message, e); // 재시도 무의미
        }catch(Exception e){
            log.error("Error parsing order-create-event message: {}",message,e);
            throw new DomainException(DomainExceptionCode.EVENT_CONSUME_ERROR);
        }
    }

    @KafkaListener(topics = "order-confirm-event", groupId = "${spring.application.name}")
    public void InventoryConfirmedEvent(String message){
        log.info("Received inventory-confirm-event message: {}", message);
        try{
            OrderConfirmResult orderConfirmResult = objectMapper.readValue(message, OrderConfirmResult.class);
            inventoryService.onInventoryConfirmed(orderConfirmResult);
        }catch(JsonProcessingException e){
            log.error("Fatal: Invalid JSON format. Message: {}", message, e); // 재시도 무의미
        }catch(Exception e){
            log.error("Error parsing order-create-event message: {}",message,e);
            throw new DomainException(DomainExceptionCode.EVENT_CONSUME_ERROR);
        }
    }

    @KafkaListener(topics = "variant-created-event", groupId = "${spring.application.name}")
    public void VariantCreatedEvent(String message){
        log.info("Received variant-created-event message: {}", message);
        try{
            VariantCreatResult variantCreatResult = objectMapper.readValue(message, VariantCreatResult.class);
            inventoryService.createInventory(variantCreatResult);
        }catch(JsonProcessingException e){
            log.error("Fatal: Invalid JSON format. Message: {}", message, e); // 재시도 무의미
        }catch(Exception e){
            log.error("Error parsing variant-created-event message: {}",message,e);
            throw new DomainException(DomainExceptionCode.EVENT_CONSUME_ERROR);
        }
    }

}
