package org.teamsparta.inventoryapi.domain.inventory.event;

import lombok.Data;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;

import java.util.UUID;

@Data
public class InventoryConfirmFailedEvent {
    private UUID eventId;
    private String eventType;
    private Long orderId;
    private UUID sagaId;
    private UUID reservationId;
    private ReservationStatus reservationStatus;

    public static InventoryConfirmFailedEvent from(Long orderId, UUID sagaId, UUID reservationId, ReservationStatus reservationStatus) {
        InventoryConfirmFailedEvent event = new InventoryConfirmFailedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.confirm.failed");
        event.setOrderId(orderId);
        event.setSagaId(sagaId);
        event.setReservationId(reservationId);
        event.setReservationStatus(reservationStatus);
        return event;
    }
}
