package org.teamsparta.productapi.domain.product.dto.response;

import org.teamsparta.productapi.domain.product.entity.ProductPlain;

public record ProductPlainSummaryResponse(
        Long id,
        String name,
        String brandName,
        Long categoryId,
        String status
) {
    public static ProductPlainSummaryResponse from(ProductPlain p) {
        return new ProductPlainSummaryResponse(
                p.getId(),
                p.getName(),
                p.getBrandName(),
                p.getCategory().getId(),
                p.getStatus().name()
        );
    }
}