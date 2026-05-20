package org.teamsparta.productapi.domain.product.dto.response;

import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductImage;
import org.teamsparta.productapi.global.enums.Status;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.Optional;

public record ProductSummaryResponse(
        Long id,
        String name,
        String brandName,
        Status status,
        Long categoryId,
        String categoryName,
        String primaryImageUrl,
        ZonedDateTime createdAt
) {
    public static ProductSummaryResponse from(Product p) {
        Optional<ProductImage> primary = p.getProductImages().stream()
                .sorted(Comparator
                        .comparing(ProductImage::getIsPrimary).reversed()
                        .thenComparing(pi -> pi.getSortOrder() == null ? 0 : pi.getSortOrder()))
                .findFirst();

        return new ProductSummaryResponse(
                p.getId(),
                p.getName(),
                p.getBrandName(),
                p.getStatus(),
                p.getCategory().getId(),
                p.getCategory().getName(),
                primary.map(ProductImage::getUrl).orElse(null),
                p.getCreatedAt()
        );
    }
}
