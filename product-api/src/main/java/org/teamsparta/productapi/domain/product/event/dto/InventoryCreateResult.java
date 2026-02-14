package org.teamsparta.productapi.domain.product.event.dto;

import java.util.UUID;

public record InventoryCreateResult(
        UUID eventId,
        String eventType,
        String sku
) {
}
