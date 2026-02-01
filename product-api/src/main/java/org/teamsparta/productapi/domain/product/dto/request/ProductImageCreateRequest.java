package org.teamsparta.productapi.domain.product.dto.request;

import org.teamsparta.productapi.global.enums.ImageType;

public record ProductImageCreateRequest(
        String storageKey,
        String url,
        ImageType type,
        Integer sortOrder,
        Boolean isPrimary
) {
}
