package org.teamsparta.orderapi.domain.order.event;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class OrderCreatedEvent {
    private UUID eventId;
    private String eventType;

    private Long orderId;
    private UUID sagaId;
    private Long userId;
    private List<Item> items;

    public static class Item{
        private String sku;
        private Integer quantity;
    }
}
