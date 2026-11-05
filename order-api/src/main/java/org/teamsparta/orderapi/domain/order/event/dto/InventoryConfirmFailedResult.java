package org.teamsparta.orderapi.domain.order.event.dto;

import java.util.UUID;

public record InventoryConfirmFailedResult(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId,
        UUID reservationId,
        String reservationStatus
) {
}
