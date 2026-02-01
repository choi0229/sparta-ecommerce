package org.teamsparta.productapi.domain.product.dto.response;

import org.teamsparta.productapi.domain.product.entity.ProductImage;
import org.teamsparta.productapi.global.enums.ImageType;

public record ProductImageResponse(
        Long id,
        String storageKey,
        String url,
        ImageType type,
        Integer sortOrder,
        Boolean isPrimary
) {
    public static ProductImageResponse from(ProductImage pi) {
        return new ProductImageResponse(
                pi.getId(),
                pi.getStorageKey(),
                pi.getUrl(),
                pi.getType(),
                pi.getSortOrder(),
                pi.getIsPrimary()
        );
    }
}
