package org.teamsparta.productapi.domain.product.dto.response;

import lombok.Builder;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductImage;
import org.teamsparta.productapi.global.enums.Status;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

@Builder
public record ProductDetailResponse(Long id,
                                    String name,
                                    String brandName,
                                    Status status,
                                    Long categoryId,
                                    String categoryName,
                                    String description,
                                    ZonedDateTime createdAt,
                                    ZonedDateTime updatedAt,
                                    List<ProductImageResponse> images,
                                    List<ProductVariantResponse> variants) {
    public static ProductDetailResponse from(Product p) {
        List<ProductImageResponse> images = p.getProductImages().stream()
                .sorted(Comparator
                        .comparing(ProductImage::getIsPrimary).reversed()
                        .thenComparing(pi -> pi.getSortOrder() == null ? 0 : pi.getSortOrder()))
                .map(ProductImageResponse::from)
                .toList();

        List<ProductVariantResponse> variants = p.getProductVariants().stream()
                .map(ProductVariantResponse::from)
                .toList();

        return new ProductDetailResponse(
                p.getId(),
                p.getName(),
                p.getBrandName(),
                p.getStatus(),
                p.getCategory().getId(),
                p.getCategory().getName(),
                p.getDescription(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                images,
                variants
        );
    }
}
