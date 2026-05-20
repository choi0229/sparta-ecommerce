package org.teamsparta.inventoryapi.domain.inventory.event;

import lombok.Data;

import java.util.UUID;

@Data
public class InventoryCreatedEvent {
    private UUID eventId;
    private String eventType;
    private String sku;

    public static InventoryCreatedEvent from(String sku){
        InventoryCreatedEvent event = new InventoryCreatedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.created");
        event.setSku(sku);
        return event;
    }
}
