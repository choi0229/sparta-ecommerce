package org.teamsparta.orderapi.domain.productProjection.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductProjectionRepository extends JpaRepository<ProductProjection, String> {
    Optional<ProductProjection> findByVariantId(Long variantId);
    List<ProductProjection> findBySkuIn(Collection<String> skus);
}
