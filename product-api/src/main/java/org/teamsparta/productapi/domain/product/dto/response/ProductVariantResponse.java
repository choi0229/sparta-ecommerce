package org.teamsparta.productapi.domain.product.dto.response;

import org.teamsparta.productapi.domain.product.entity.ProductVariant;
import org.teamsparta.productapi.global.enums.Status;

import java.math.BigDecimal;

public record ProductVariantResponse(
        Long id,
        String sku,
        BigDecimal price,
        Status status,
        Object optionJson
) {
    public static ProductVariantResponse from(ProductVariant v) {
        return new ProductVariantResponse(
                v.getId(),
                v.getSku(),
                v.getPrice(),
                v.getStatus(),
                v.getOptionJson()
        );
    }
}
