package org.teamsparta.productapi.domain.product.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.productapi.domain.product.event.dto.InventoryCreateResult;
import org.teamsparta.productapi.domain.product.service.ProductService;
import org.teamsparta.productapi.domain.product.service.ProductVariantService;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventConsumer {

    private final ObjectMapper objectMapper;
    private final ProductVariantService productVariantService;

    @KafkaListener(topics = "inventory-created-event", groupId = "${spring.application.name}")
    public void PaymentRequestFailedEvent(String message){
        log.info("Received inventory-created-event message: {}", message);
        try{
            InventoryCreateResult inventoryCreateResult = objectMapper.readValue(message, InventoryCreateResult.class);
            productVariantService.activeProductVariant(inventoryCreateResult);
        }catch(Exception e){
            log.error("Error parsing inventory-created-event message: {}", message, e);
        }
    }
}
