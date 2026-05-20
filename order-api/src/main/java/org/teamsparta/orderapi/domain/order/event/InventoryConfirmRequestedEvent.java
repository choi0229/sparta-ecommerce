package org.teamsparta.orderapi.domain.order.event;

import lombok.Data;

import java.util.UUID;

@Data
public class InventoryConfirmRequestedEvent {
    private UUID eventId;
    private String eventType;
    private Long orderId;
    private UUID sagaId;
    private UUID reservationId;

    public static InventoryConfirmRequestedEvent from(Long orderId, UUID sagaId, UUID reservationId) {
        InventoryConfirmRequestedEvent event = new InventoryConfirmRequestedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.confirmed");
        event.setOrderId(orderId);
        event.setSagaId(sagaId);
        event.setReservationId(reservationId);
        return event;
    }
}
