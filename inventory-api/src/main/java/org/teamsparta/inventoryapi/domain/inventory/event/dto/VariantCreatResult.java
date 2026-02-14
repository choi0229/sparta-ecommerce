package org.teamsparta.inventoryapi.domain.inventory.event.dto;

import java.util.UUID;

public record VariantCreatResult(
        UUID eventId,
        String eventType,
        String sku,
        Integer totalQuantity
) {
}
