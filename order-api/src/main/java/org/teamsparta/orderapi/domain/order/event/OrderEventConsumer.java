package org.teamsparta.orderapi.domain.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderSagaService orderSagaService;

    @KafkaListener(topics = "inventory-reserved-event", groupId = "${spring.application.name}")
    public void InventoryReservedEvent(String message){
        log.info("Received inventory-reserved-event message: {}", message);
        try{
            InventoryReservedResult inventoryReservedResult = objectMapper.readValue(message, InventoryReservedResult.class);
            orderSagaService.onInventoryReserved(inventoryReservedResult);
        }catch(Exception e){
            log.error("Error parsing inventory-reserved-event message: {}", message, e);
        }
    }
}