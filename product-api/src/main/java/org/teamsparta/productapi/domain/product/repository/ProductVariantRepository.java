package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;

import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    boolean existsBySku(String sku);
    Optional<ProductVariant> findBySku(String sku);
}
