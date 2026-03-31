package org.teamsparta.productapi.domain.product.dto.response;

import org.teamsparta.productapi.domain.product.entity.ProductDocument;

public record ProductSummaryEsResponse(
        Long id,
        String name,
        String brandName,
        Long categoryId,
        String status
) {
    public static ProductSummaryEsResponse fromDocument(ProductDocument doc) {
        return new ProductSummaryEsResponse(
                doc.getId(),
                doc.getName(),
                doc.getBrandName(),
                doc.getCategoryId(),
                doc.getStatus()
        );
    }
}