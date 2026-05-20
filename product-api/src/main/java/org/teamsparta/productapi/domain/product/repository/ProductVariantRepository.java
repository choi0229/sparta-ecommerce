package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, Long> {
    boolean existsBySku(String sku);
    Optional<ProductVariant> findBySku(String sku);
    @Query("SELECT pv FROM ProductVariant pv JOIN FETCH pv.product WHERE pv.sku IN :skus")
    List<ProductVariant> findBySkuIn(List<String> skus);
}
