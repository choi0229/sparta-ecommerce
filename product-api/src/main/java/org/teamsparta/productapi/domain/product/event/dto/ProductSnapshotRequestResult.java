package org.teamsparta.productapi.domain.product.event.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProductSnapshotRequestResult(
        UUID requestId,
        String eventType,
        List<Item> items,
        String idemKey,
        Long userId,
        ShippingAddress shippingAddress
) {
    public record Item(
            String sku,
            Integer quantity
    ) {}

    public record ShippingAddress(
            String recipientName,
            String recipientAddress
    ) {}
}