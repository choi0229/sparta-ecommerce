package org.teamsparta.productapi.domain.category.dto;

import lombok.Builder;

import java.util.List;

@Builder
public record CategoryResponse(Long id, String name, List<CategoryResponse> childCategories) {
}
