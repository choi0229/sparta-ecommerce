package org.teamsparta.orderapi.domain.order.event.dto;

import java.util.UUID;

public record InventoryConfirmedResult(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId
) {
}
