package org.teamsparta.productapi.domain.product.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.teamsparta.productapi.domain.product.dto.response.ProductDetailResponse;
import org.teamsparta.productapi.domain.product.service.ProductService;
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
}
