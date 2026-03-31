package org.teamsparta.productapi.domain.product.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.productapi.domain.product.dto.response.ProductDetailResponse;
import org.teamsparta.productapi.domain.product.dto.response.ProductPlainSummaryResponse;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryEsResponse;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryResponse;
import org.teamsparta.productapi.domain.product.service.ProductEsService;
import org.teamsparta.productapi.domain.product.service.ProductPlainService;
import org.teamsparta.productapi.domain.product.service.ProductService;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.response.ApiResponse;

@RequestMapping("/api/products")
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductEsService productEsService;
    private final ProductPlainService productPlainService;

    // 상품 상세 조회 (공통)
    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProduct(@PathVariable Long productId) {
        return ApiResponse.ok(productService.getProductDetail(productId));
    }

    // 1. RDB LIKE 검색 (기준선)
    @GetMapping
    public ApiResponse<Page<ProductSummaryResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Status status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(productService.searchProducts(keyword, brandName, categoryId, status, pageable));
    }

    // 2. FTS 검색 (PostgreSQL GIN 인덱스)
    @GetMapping("/fts/search")
    public ApiResponse<Page<ProductSummaryResponse>> searchFts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Status status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(productService.searchProductsFts(keyword, brandName, categoryId, status, pageable));
    }

    // 3. ES 검색 (ElasticSearch)
    @GetMapping("/es/search")
    public ApiResponse<Page<ProductSummaryEsResponse>> searchEs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Status status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(productEsService.searchProducts(keyword, brandName, categoryId, status, pageable));
    }

    @GetMapping("/plain/search")
    public ApiResponse<Page<ProductPlainSummaryResponse>> searchPlain(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Status status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(productPlainService.searchProducts(keyword, brandName, categoryId, status, pageable));
    }

    // 5. Plain FTS 검색
    @GetMapping("/plain/fts/search")
    public ApiResponse<Page<ProductPlainSummaryResponse>> searchPlainFts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Status status,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return ApiResponse.ok(productPlainService.searchProductsFts(keyword, brandName, categoryId, status, pageable));
    }
}