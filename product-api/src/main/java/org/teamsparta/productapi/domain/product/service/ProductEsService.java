package org.teamsparta.productapi.domain.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.teamsparta.productapi.domain.product.dto.response.ProductSummaryEsResponse;
import org.teamsparta.productapi.domain.product.entity.ProductDocument;
import org.teamsparta.productapi.domain.product.repository.ProductEsRepository;
import org.teamsparta.productapi.global.enums.Status;

@Service
@RequiredArgsConstructor
public class ProductEsService {

    private final ProductEsRepository productEsRepository;

    public Page<ProductSummaryEsResponse> searchProducts(
            String keyword,
            String brandName,
            Long categoryId,
            Status status,
            Pageable pageable
    ) {
        Page<ProductDocument> result;

        if(StringUtils.hasText(keyword) && categoryId != null) {
            result = productEsRepository
                    .findByNameContainingAndCategoryId(keyword, categoryId, pageable);
        }else if(StringUtils.hasText(keyword) && StringUtils.hasText(brandName)){
            result = productEsRepository
                    .findByNameContainingOrBrandNameContaining(keyword, brandName, pageable);
        }else if (StringUtils.hasText(keyword)) {
            result = productEsRepository
                    .findByNameContainingOrBrandNameContaining(keyword, keyword, pageable);
        } else if (StringUtils.hasText(brandName)) {
            result = productEsRepository
                    .findByBrandNameContaining(brandName, pageable);
        } else {
            result = productEsRepository.findAll(pageable);
        }
        return result.map(doc -> ProductSummaryEsResponse.fromDocument(doc));
    }
}
