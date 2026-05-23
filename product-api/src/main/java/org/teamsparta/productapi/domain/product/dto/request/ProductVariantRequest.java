package org.teamsparta.productapi.domain.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Map;

public record ProductVariantRequest(
        @NotBlank @Size(max = 80) String sku,
        @NotNull @Positive BigDecimal price,
        Integer stockQuantity,
        Map<String, Object> optionJson
) {
}
