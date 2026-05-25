package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.teamsparta.productapi.domain.product.entity.ProductImage;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /**
     * 주어진 productId 목록에 속하는 이미지를 한 번의 IN 쿼리로 조회한다.
     * searchProducts N+1 방지용 batch 조회 메서드.
     */
    List<ProductImage> findByProductIdIn(Collection<Long> productIds);
}
