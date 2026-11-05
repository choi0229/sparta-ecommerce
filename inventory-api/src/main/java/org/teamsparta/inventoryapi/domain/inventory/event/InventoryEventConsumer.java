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

@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryEventConsumer {

    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    // 예외를 잡지 않는다. JSON 오류와 시스템 예외는 KafkaConfig의 DefaultErrorHandler가 처리한다.

    @KafkaListener(topics = "order-create-event", groupId = "${spring.application.name}")
    public void orderCreateEvent(String message) throws JsonProcessingException {
        log.info("Received order-create-event message: {}", message);
        inventoryService.reserveInventory(objectMapper.readValue(message, OrderCreateResult.class));
    }

    @KafkaListener(topics = "order-confirm-event", groupId = "${spring.application.name}")
    public void InventoryConfirmedEvent(String message) throws JsonProcessingException {
        log.info("Received order-confirm-event message: {}", message);
        inventoryService.onInventoryConfirmed(objectMapper.readValue(message, OrderConfirmResult.class));
    }

    @KafkaListener(topics = "inventory-init-event", groupId = "${spring.application.name}")
    public void VariantCreatedEvent(String message) throws JsonProcessingException {
        log.info("Received inventory-init-event message: {}", message);
        inventoryService.createInventory(objectMapper.readValue(message, VariantCreatResult.class));
    }
}
