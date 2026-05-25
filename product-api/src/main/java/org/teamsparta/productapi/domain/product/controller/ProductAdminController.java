package org.teamsparta.productapi.domain.product.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.teamsparta.productapi.domain.product.dto.request.ProductCreateRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductImageAddRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductImageCreateRequest;
import org.teamsparta.productapi.domain.product.service.ProductService;
import org.teamsparta.productapi.domain.product.service.S3Service;
import org.teamsparta.productapi.global.enums.ImageType;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;
import org.teamsparta.productapi.global.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/admin/products")
@RequiredArgsConstructor
public class ProductAdminController {

    private final ProductService productService;
    private final S3Service s3Service;

    @PostMapping
    public ApiResponse<Void> createProduct(@Valid @RequestBody ProductCreateRequest request){
        productService.createProduct(request);
        return ApiResponse.ok();
    }

    @PostMapping("/{productId}/images")
    public ApiResponse<Void> addImages(
            @PathVariable Long productId,
            @RequestBody List<ProductImageAddRequest> images
    ) {
        productService.addImages(productId, images);
        return ApiResponse.ok();
    }

    @PostMapping(value="/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductImageCreateRequest> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "DETAIL") ImageType type
    ){
        if(file.isEmpty()){
            throw new DomainException(DomainExceptionCode.FILE_UPLOAD);
        }
        return ApiResponse.ok(s3Service.uploadFile(file, type));
    }
}
