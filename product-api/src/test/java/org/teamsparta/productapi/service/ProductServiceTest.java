package org.teamsparta.productapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.domain.product.dto.request.ProductCreateRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductVariantRequest;
import org.teamsparta.productapi.global.enums.Status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class ProductServiceTest {

    @Test
    @DisplayName("상품 생성 성공 - SKU 중복이 없고 카테고리가 존재할 때")
    void createProduct_Success(){
        // given
        Long categoryId = 1L;
        ProductVariantRequest variantRequest = new ProductVariantRequest("TEST-SKU-001", BigDecimal.valueOf(10000), 10, Map.of("color", "black"));
        ProductCreateRequest productCreateRequest = new ProductCreateRequest("TEST", "Brand-Test", categoryId, "설명", List.of(variantRequest), null);
        Category category = Category.builder()
                .name("테스트 카테고리")
                .status(Status.ACTIVE)
                .sortOrder(0)
                .parent(null)
                .build();
    }
}
