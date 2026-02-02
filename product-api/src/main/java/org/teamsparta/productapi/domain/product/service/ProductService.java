package org.teamsparta.productapi.domain.product.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.productapi.domain.category.entity.Category;
import org.teamsparta.productapi.domain.category.repository.CategoryRepository;
import org.teamsparta.productapi.domain.product.dto.request.ProductCreateRequest;
import org.teamsparta.productapi.domain.product.dto.request.ProductImageAddRequest;
import org.teamsparta.productapi.domain.product.dto.response.ProductDetailResponse;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryResponse;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductImage;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;
import org.teamsparta.productapi.domain.product.repository.ProductRepository;
import org.teamsparta.productapi.domain.product.repository.ProductSpecs;
import org.teamsparta.productapi.domain.product.repository.ProductVariantRepository;
import org.teamsparta.productapi.global.enums.ImageType;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductVariantRepository productVariantRepository;

    @Transactional
    public void createProduct(ProductCreateRequest request) {
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_CATEGORY));

        if(request.variants() != null) {
            for(var v : request.variants()) {
                if(productVariantRepository.existsBySku(v.sku())){
                    throw new DomainException(DomainExceptionCode.DUPLICATE_SKU);
                }
            }
        }

        Product product = Product.builder()
                .name(request.name())
                .brandName(request.brandName())
                .category(category)
                .description(request.description())
                .status(Status.ACTIVE)
                .build();

        // images
        if(request.images() != null) {
            for(var img : request.images()) {
                ProductImage entity = ProductImage.builder()
                        .product(product)
                        .storageKey(img.storageKey())
                        .url(img.url())
                        .type(img.type())
                        .sortOrder(img.sortOrder() == null ? 0 : img.sortOrder())
                        .isPrimary(Boolean.TRUE.equals(img.isPrimary()))
                        .build();
                product.getProductImages().add(entity);
            }
        }

        // variants
        if(request.variants() != null) {
            for(var v : request.variants()) {
                ProductVariant variant = ProductVariant.builder()
                        .product(product)
                        .sku(v.sku())
                        .price(BigDecimal.valueOf(v.price()))
                        .optionJson(v.optionJson())
                        .build();
                product.getProductVariants().add(variant);
            }
        }

        // TODO : kafka 재고 등록 퍼블리셔 추가하기

        Product saved = productRepository.save(product);
    }

    @Transactional
    public void addImages(Long productId, List<ProductImageAddRequest> images){
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_PRODUCT));

        // 1. 요청에 '대표 지정'이 포함되어 있는지 확인
        boolean hasPrimaryInRequest = images.stream()
                .anyMatch(i -> Boolean.TRUE.equals(i.isPrimary()));

        // 2. 새로운 대표가 들어오면 기존 대표는 모두 해제 (Dirty Checking으로 자동 반영)
        if(hasPrimaryInRequest){
            product.getProductImages().stream()
                    .filter(ProductImage::getIsPrimary)
                    .forEach(pi -> pi.updatePrimary(false));
        }

        // 3. 새로운 이미지 추가
        for(var img : images){
            ProductImage entity = ProductImage.builder()
                    .product(product)
                    .storageKey(img.storageKey())
                    .url(img.url())
                    .type(img.type())
                    .sortOrder(img.sortOrder())
                    .isPrimary(Boolean.TRUE.equals(img.isPrimary()))
                    .build();
            product.getProductImages().add(entity);
        }

        // 4. 대표가 여전히 없다면 '순서가 가장 빠른 것'을 자동으로 대표 지정
        boolean currentHasPrimary = product.getProductImages().stream().anyMatch(ProductImage::getIsPrimary);
        if(!currentHasPrimary && !product.getProductImages().isEmpty()){
            product.getProductImages().stream()
                    .min(Comparator.comparingInt(ProductImage::getSortOrder))
                    .ifPresent(first -> first.updatePrimary(true));
        }
    }

    @Transactional(readOnly = true)
    public ProductDetailResponse getProductDetail(Long productId){
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_PRODUCT));
        
        return ProductDetailResponse.from(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductSummaryResponse> searchProducts(
            String keyword,
            String brandName,
            Long categoryId,
            Status status,
            Pageable pageable
    ){
        Specification<Product> spec = Specification.where(ProductSpecs.nameLike(keyword))
                .and(ProductSpecs.brandLike(brandName))
                .and(ProductSpecs.categoryEq(categoryId))
                .and(ProductSpecs.statusEq(status));

        Page<Product> productPage = productRepository.findAll(spec, pageable);
        return productPage.map(ProductSummaryResponse::from);
    }

}
