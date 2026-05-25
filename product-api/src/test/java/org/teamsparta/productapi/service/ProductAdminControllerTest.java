package org.teamsparta.productapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.productapi.domain.product.controller.ProductAdminController;
import org.teamsparta.productapi.domain.product.dto.request.ProductCreateRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductVariantRequest;
import org.teamsparta.productapi.domain.product.service.ProductService;
import org.teamsparta.productapi.domain.product.service.S3Service;
import org.teamsparta.productapi.global.config.ObjectMapperConfig;
import org.teamsparta.productapi.global.exception.GlobalExceptionHandler;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductAdminController.class)
@Import({GlobalExceptionHandler.class, ObjectMapperConfig.class})
class ProductAdminControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ProductService productService;

    @MockBean
    S3Service s3Service;

    // ── 정상 요청 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("정상 요청 — 200 반환, productService.createProduct() 호출")
    void createProduct_valid_returns200() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "테스트상품", "브랜드", 1L, "설명",
                List.of(new ProductVariantRequest("SKU-1", BigDecimal.valueOf(1000), 10, null)),
                null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        verify(productService).createProduct(any());
    }

    // ── 상품명 validation ────────────────────────────────────────────────────

    @Test
    @DisplayName("상품명 blank → 400, productService 호출 없음")
    void createProduct_blankName_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "", "브랜드", 1L, "설명", null, null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }

    @Test
    @DisplayName("상품명 200자 초과 → 400")
    void createProduct_nameTooLong_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "a".repeat(201), "브랜드", 1L, "설명", null, null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }

    // ── 브랜드명 validation ───────────────────────────────────────────────────

    @Test
    @DisplayName("브랜드명 blank → 400")
    void createProduct_blankBrandName_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "상품", "  ", 1L, "설명", null, null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }

    // ── categoryId validation ─────────────────────────────────────────────────

    @Test
    @DisplayName("categoryId null → 400")
    void createProduct_nullCategoryId_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "상품", "브랜드", null, "설명", null, null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }

    // ── variant 중첩 validation ───────────────────────────────────────────────

    @Test
    @DisplayName("variant sku blank → 400 (중첩 @Valid)")
    void createProduct_variantBlankSku_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "상품", "브랜드", 1L, "설명",
                List.of(new ProductVariantRequest("", BigDecimal.valueOf(1000), 10, null)),
                null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }

    @Test
    @DisplayName("variant price null → 400 (중첩 @Valid)")
    void createProduct_variantNullPrice_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "상품", "브랜드", 1L, "설명",
                List.of(new ProductVariantRequest("SKU-1", null, 10, null)),
                null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }

    @Test
    @DisplayName("variant price 음수 → 400 (중첩 @Valid)")
    void createProduct_variantNegativePrice_returns400() throws Exception {
        ProductCreateRequest req = new ProductCreateRequest(
                "상품", "브랜드", 1L, "설명",
                List.of(new ProductVariantRequest("SKU-1", BigDecimal.valueOf(-1), 10, null)),
                null
        );

        mockMvc.perform(post("/api/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        verify(productService, never()).createProduct(any());
    }
}
