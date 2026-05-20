package org.teamsparta.orderapi.domain.order.event.dto;

import java.util.UUID;

public record InventoryReservationExpiredResult(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId,
        UUID reservationId
) {
}
