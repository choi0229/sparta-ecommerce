package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.teamsparta.productapi.domain.product.entity.ProductPlain;

import java.util.List;

@Repository
public interface ProductPlainRepository extends JpaRepository<ProductPlain, Long> {
    @Query(
            value = """
        SELECT * FROM product_plain p
        WHERE (:keyword IS NULL OR p.name_tsv @@ to_tsquery('simple', :tsquery))
          AND (:brandName IS NULL OR p.brand_tsv @@ to_tsquery('simple', :brandTsquery))
          AND (:categoryId IS NULL OR p.category_id = :categoryId)
          AND (:status IS NULL OR p.status = :status)
        ORDER BY p.id DESC
        LIMIT :size OFFSET :offset
        """,
            nativeQuery = true
    )
    List<ProductPlain> searchByFts(
            @Param("keyword") String keyword,
            @Param("tsquery") String tsquery,
            @Param("brandName") String brandName,
            @Param("brandTsquery") String brandTsquery,
            @Param("categoryId") Long categoryId,
            @Param("status") String status,
            @Param("size") int size,
            @Param("offset") long offset
    );

    @Query(
            value = """
        SELECT COUNT(*) FROM product_plain p
        WHERE (:keyword IS NULL OR p.name_tsv @@ to_tsquery('simple', :tsquery))
          AND (:brandName IS NULL OR p.brand_tsv @@ to_tsquery('simple', :brandTsquery))
          AND (:categoryId IS NULL OR p.category_id = :categoryId)
          AND (:status IS NULL OR p.status = :status)
        """,
            nativeQuery = true
    )
    Long countByFts(
            @Param("keyword") String keyword,
            @Param("tsquery") String tsquery,
            @Param("brandName") String brandName,
            @Param("brandTsquery") String brandTsquery,
            @Param("categoryId") Long categoryId,
            @Param("status") String status
    );
}