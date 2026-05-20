package org.teamsparta.productapi.domain.product.dto.request;

import java.util.List;

public record ProductCreateRequest(
        String name,
        String brandName,
        Long categoryId,
        String description,
        List<ProductVariantRequest> variants,
        List<ProductImageCreateRequest> images
) {
}
