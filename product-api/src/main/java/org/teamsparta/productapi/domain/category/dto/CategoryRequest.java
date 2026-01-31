package org.teamsparta.productapi.domain.category.dto;

import jakarta.validation.constraints.NotBlank;
import org.teamsparta.productapi.global.enums.Status;

public record CategoryRequest(
        @NotBlank String name,
        Long parentId,
        Integer sortOrder,
        Status status
) {
}
