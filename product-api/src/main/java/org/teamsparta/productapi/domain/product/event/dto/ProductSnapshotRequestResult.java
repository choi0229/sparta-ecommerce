package org.teamsparta.productapi.domain.product.event.dto;

import java.util.List;
import java.util.UUID;

public record ProductSnapshotRequestResult(
        UUID requestId,
        String eventType,
        List<Item> items,
        String idemKey,
        Long userId
) {
    public record Item(
            String sku,
            Integer quantity
    ) {}
}