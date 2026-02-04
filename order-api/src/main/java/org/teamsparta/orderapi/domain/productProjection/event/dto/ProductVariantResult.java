package org.teamsparta.orderapi.domain.productProjection.event.dto;

import java.math.BigDecimal;
import java.util.Map;

public record ProductVariantResult(
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
