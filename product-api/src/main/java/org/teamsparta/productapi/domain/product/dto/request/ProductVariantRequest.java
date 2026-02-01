package org.teamsparta.productapi.domain.product.dto.request;

import java.util.Map;

public record ProductVariantRequest(
        String sku,
        Long price,
        Integer stockQuantity,
        Map<String, Object> optionJson
) {
}
