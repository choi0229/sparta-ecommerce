package org.teamsparta.productapi.domain.product.event;

import lombok.Data;

import java.util.UUID;

@Data
public class ProductInventoryEvent {
    private UUID eventId;
    private String eventType;

    private String sku;
    private Integer totalQuantity;

    public static ProductInventoryEvent from(String sku, Integer totalQuantity) {
        ProductInventoryEvent event = new ProductInventoryEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.created");
        event.setSku(sku);
        event.setTotalQuantity(totalQuantity);
        return event;
    }
}
