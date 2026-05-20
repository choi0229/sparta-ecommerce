package org.teamsparta.orderapi.domain.productProjection.event.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record ProductVariantResult(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,

        String sku,
        Long variantId,
        String productName,
        Long productId,
        BigDecimal price,
        String status,
        Map<String, Object>optionJson,
        String categoryPath
) {
}
