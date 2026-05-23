package org.teamsparta.productapi.domain.product.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProductCreateRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 120) String brandName,
        @NotNull Long categoryId,
        String description,
        @Valid List<ProductVariantRequest> variants,
        List<ProductImageCreateRequest> images
) {
}
