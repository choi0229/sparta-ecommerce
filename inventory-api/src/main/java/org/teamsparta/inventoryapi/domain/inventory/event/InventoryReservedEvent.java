package org.teamsparta.inventoryapi.domain.inventory.event;

import lombok.Data;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class InventoryReservedEvent {
    private UUID eventId;
    private String eventType;
    private UUID sagaId;
    private Long orderId;
    private UUID reservationId;
    private ZonedDateTime expiresAt;
    private List<Item> items;

    public record Item(String sku, Integer quantity) {}

    public static InventoryReservedEvent from(Long orderId, UUID sagaId, UUID reservationId, ZonedDateTime expiresAt, List<Item> items) {
        InventoryReservedEvent event = new InventoryReservedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("inventory.reserved");
        event.setSagaId(sagaId);
        event.setOrderId(orderId);
        event.setReservationId(reservationId);
        event.setExpiresAt(expiresAt);
        event.setItems(items);
        return event;
    }
}
