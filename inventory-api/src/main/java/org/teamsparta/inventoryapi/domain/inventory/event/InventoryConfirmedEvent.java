package org.teamsparta.inventoryapi.domain.inventory.event;

import lombok.Data;

import java.util.UUID;

@Data
public class InventoryConfirmedEvent {
    private UUID eventId;
    private String eventType;
    private Long orderId;
    private UUID sagaId;

    public static InventoryConfirmedEvent from(Long orderId, UUID sagaId) {
        InventoryConfirmedEvent event = new InventoryConfirmedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.confirmed");
        event.setOrderId(orderId);
        event.setSagaId(sagaId);
        return event;
    }
}
