package org.teamsparta.productapi.domain.product.dto.response;

import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductImage;
import org.teamsparta.productapi.global.enums.Status;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
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
    /**
     * batch 로딩된 images 리스트를 받아 DTO를 조립한다.
     * searchProducts에서 N+1을 피하기 위해 이 오버로드를 사용한다.
     */
    public static ProductSummaryResponse from(Product p, List<ProductImage> images) {
        Optional<ProductImage> primary = images.stream()
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

    /**
     * 단일 Product 엔티티에서 직접 변환할 때 사용한다 (product.getProductImages() 접근).
     * 목록 조회에서는 N+1이 발생하므로 from(Product, List) 오버로드를 사용할 것.
     */
    public static ProductSummaryResponse from(Product p) {
        return from(p, p.getProductImages());
    }
}
