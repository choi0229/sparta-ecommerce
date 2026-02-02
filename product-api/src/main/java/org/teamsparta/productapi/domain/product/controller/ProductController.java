package org.teamsparta.productapi.domain.product.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.productapi.domain.product.dto.response.ProductDetailResponse;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryResponse;
import org.teamsparta.productapi.domain.product.service.ProductService;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.response.ApiResponse;

@RequestMapping("/api/products")
@RestController
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailResponse> getProduct(@PathVariable Long productId){
        return ApiResponse.ok(productService.getProductDetail(productId));
    }

    @GetMapping
    public ApiResponse<Page<ProductSummaryResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String brandName,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Status status,
            @PageableDefault(size = 20) Pageable pageable
    ){
        return ApiResponse.ok(productService.searchProducts(keyword, brandName, categoryId, status, pageable));
    }
}
