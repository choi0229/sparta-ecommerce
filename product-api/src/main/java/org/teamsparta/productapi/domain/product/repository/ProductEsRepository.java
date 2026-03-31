package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.teamsparta.productapi.domain.product.entity.ProductDocument;

public interface ProductEsRepository extends ElasticsearchRepository<ProductDocument, Long> {

    // 키워드 검색
    Page<ProductDocument> findByNameContainingOrBrandNameContaining(String name, String brandName, Pageable pageable);

    // 키워드 + 카테고리
    Page<ProductDocument> findByNameContainingAndCategoryId(String nane, Long categoryId, Pageable pageable);

    // 브랜드만
    Page<ProductDocument> findByBrandNameContaining(String brandName, Pageable pageable);
}
