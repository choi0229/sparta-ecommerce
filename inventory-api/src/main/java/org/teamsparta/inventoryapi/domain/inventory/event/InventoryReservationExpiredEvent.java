package org.teamsparta.inventoryapi.domain.inventory.event;

import lombok.Data;

import java.util.UUID;

@Data
public class InventoryReservationExpiredEvent{
    private UUID eventId;
    private String eventType;
    private Long orderId;
    private UUID sagaId;
    private UUID reservationId;

    public static InventoryReservationExpiredEvent from(Long orderId, UUID sagaId, UUID reservationId){
        InventoryReservationExpiredEvent event = new InventoryReservationExpiredEvent();
        event.eventId = UUID.randomUUID();
        event.eventType = "inventory.expired";
        event.orderId = orderId;
        event.sagaId = sagaId;
        event.reservationId = reservationId;
        return event;
    }
}
