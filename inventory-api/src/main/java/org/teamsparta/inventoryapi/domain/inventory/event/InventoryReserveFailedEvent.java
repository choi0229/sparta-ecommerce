package org.teamsparta.inventoryapi.domain.inventory.event;

import lombok.Data;

import java.util.UUID;

@Data
public class InventoryReserveFailedEvent {
    private UUID eventId;
    private String eventType;
    private UUID sagaId;
    private Long orderId;
    private String message;

    public static InventoryReserveFailedEvent from(UUID sagaId, Long orderId, String message) {
        InventoryReserveFailedEvent event = new InventoryReserveFailedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.reserved.failed");
        event.setSagaId(sagaId);
        event.setOrderId(orderId);
        event.setMessage(message);
        return event;

    }
}
