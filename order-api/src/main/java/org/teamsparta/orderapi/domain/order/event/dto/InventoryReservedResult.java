package org.teamsparta.orderapi.domain.order.event.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public record InventoryReservedResult(
        UUID eventId,
        String eventType,
        UUID sagaId,
        Long orderId,
        UUID reservationId,
        ZonedDateTime expiresAt,
        List<Item> items
) {
    public record Item(
            String sku,
            Integer quantity
    ) {}
}
