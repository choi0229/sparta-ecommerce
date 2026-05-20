package org.teamsparta.inventoryapi.domain.inventory.event.dto;

import java.util.List;
import java.util.UUID;

public record OrderCreateResult(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId,
        Long userId,
        List<Item> items
) {
    public record Item(
            String sku,
            Integer quantity
    ) {}
}
