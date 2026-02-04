package org.teamsparta.productapi.domain.product.event;

import lombok.Data;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
public class ProductVariantEvent {
    private UUID eventId;
    private String eventType;
    private LocalDateTime occurredAt;

    private String sku;
    private Long variantId;
    private String productName;
    private Long productId;
    private BigDecimal price;
    private String status;
    private Map<String, Object> optionJson;
    private String categoryPath;

    public static ProductVariantEvent from(Product product, ProductVariant productVariant){
        ProductVariantEvent event = new ProductVariantEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("product.variant.upserted");
        event.setOccurredAt(LocalDateTime.now());

        event.setSku(productVariant.getSku());
        event.setVariantId(productVariant.getId());
        event.setProductId(product.getId());
        event.setProductName(product.getName());
        event.setPrice(productVariant.getPrice());
        event.setStatus(product.getStatus().name());
        event.setOptionJson(productVariant.getOptionJson());
        event.setCategoryPath(product.getCategory().getName());
        return event;
    }
}
