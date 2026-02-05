package org.teamsparta.productapi.domain.product.dto.request;

import java.math.BigDecimal;
import java.util.Map;

public record ProductVariantRequest(
        String sku,
        BigDecimal price,
        Integer stockQuantity,
        Map<String, Object> optionJson
) {
}
