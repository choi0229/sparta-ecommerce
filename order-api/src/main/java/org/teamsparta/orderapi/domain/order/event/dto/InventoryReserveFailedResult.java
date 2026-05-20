package org.teamsparta.orderapi.domain.order.event.dto;

import java.util.UUID;

public record InventoryReserveFailedResult(
        UUID eventId,
        String eventType,
        UUID sagaId,
        Long orderId,
        String message
) {
}
