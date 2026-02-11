package org.teamsparta.inventoryapi.domain.inventory.event.dto;

import java.util.UUID;

public record OrderConfirmResult(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId,
        UUID reservationId
) {
}
