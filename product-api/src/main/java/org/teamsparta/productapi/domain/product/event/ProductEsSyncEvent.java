package org.teamsparta.productapi.domain.product.event;

import org.teamsparta.productapi.domain.product.entity.Product;

public record ProductEsSyncEvent(
        Long id,
        String name,
        String brandName,
        Long categoryId,
        String status,
        String description,
        String action  // CREATE, UPDATE, DELETE
) {
    public static ProductEsSyncEvent from(Product product, String action) {
        return new ProductEsSyncEvent(
                product.getId(),
                product.getName(),
                product.getBrandName(),
                product.getCategory().getId(),
                product.getStatus().name(),
                product.getDescription(),
                action
        );
    }
}
