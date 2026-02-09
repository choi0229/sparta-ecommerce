package org.teamsparta.orderapi.domain.order.event;

import lombok.Data;
import org.teamsparta.orderapi.domain.order.entity.OrderItem;
import org.teamsparta.orderapi.domain.order.entity.Orders;

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

        public Item(String sku, Integer quantity) {
            this.sku = sku;
            this.quantity = quantity;
        }
    }

    public static OrderCreatedEvent from(Orders order, List<OrderItem> orderItems){
        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("order.created");
        event.setOrderId(order.getId());
        event.setSagaId(order.getSagaId());
        event.setUserId(order.getUserId());
        event.setItems(orderItems.stream()
                .map(item -> new Item(item.getSku(), item.getQuantity()))
                .toList());
        return event;
    }
}
