package org.teamsparta.productapi.domain.product.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.productapi.domain.product.entity.ProductDocument;
import org.teamsparta.productapi.domain.product.event.dto.InventoryCreateResult;
import org.teamsparta.productapi.domain.product.event.dto.ProductSnapshotRequestResult;
import org.teamsparta.productapi.domain.product.repository.ProductEsRepository;
import org.teamsparta.productapi.domain.product.service.ProductVariantService;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventConsumer {

    private final ObjectMapper objectMapper;
    private final ProductVariantService productVariantService;
    private final ProductEsRepository productEsRepository;

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

    @KafkaListener(topics = "productSnapshot-requested-event", groupId = "${spring.application.name}")
    public void ProductSnapshotRequestedEvent(String message){
        log.info("Received productSnapshot-requested-event message: {}", message);
        try{
            ProductSnapshotRequestResult productSnapshotRequestResult = objectMapper.readValue(message, ProductSnapshotRequestResult.class);
            productVariantService.replyProductSnapshot(productSnapshotRequestResult);
        }catch(Exception e){
            log.error("Error parsing productSnapshot-requested-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "es-sync-event", groupId = "${spring.application.name}")
    public void esSyncEvent(String message) {
        log.info("Received es-sync-event message: {}", message);
        try {
            ProductEsSyncEvent event = objectMapper.readValue(message, ProductEsSyncEvent.class);

            switch (event.action()) {
                case "CREATE", "UPDATE" -> {
                    ProductDocument doc = ProductDocument.builder()
                            .id(event.id())
                            .name(event.name())
                            .brandName(event.brandName())
                            .categoryId(event.categoryId())
                            .status(event.status())
                            .description(event.description())
                            .build();
                    productEsRepository.save(doc);
                    log.info("ES sync [{}] id={}", event.action(), event.id());
                }
                case "DELETE" -> {
                    productEsRepository.deleteById(event.id());
                    log.info("ES sync [DELETE] id={}", event.id());
                }
                default -> log.warn("Unknown ES sync action: {}", event.action());
            }
        } catch (Exception e) {
            log.error("Error parsing es-sync-event message: {}", message, e);
        }
    }
}
