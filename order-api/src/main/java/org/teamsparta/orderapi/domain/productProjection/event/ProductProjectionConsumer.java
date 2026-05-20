package org.teamsparta.orderapi.domain.productProjection.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.productProjection.event.dto.ProductVariantResult;
import org.teamsparta.orderapi.domain.productProjection.service.ProductProjectionService;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductProjectionConsumer {

    private final ProductProjectionService productProjectionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "product-variant-event", groupId = "${spring.application.name}")
    public void onVariantUpsertEvent(String message)throws Exception{
        log.info("Received product-variant-event. message={}", message);
        ProductVariantResult productVariantResult = objectMapper.readValue(message,ProductVariantResult.class);

        productProjectionService.updateProductProjection(productVariantResult);
    }
}
