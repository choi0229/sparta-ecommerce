package org.teamsparta.productapi.domain.product.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.teamsparta.productapi.domain.product.dto.response.ProductPlainSummaryResponse;
import org.teamsparta.productapi.domain.product.entity.ProductPlain;
import org.teamsparta.productapi.domain.product.repository.ProductPlainQueryRepository;
import org.teamsparta.productapi.domain.product.repository.ProductPlainRepository;
import org.teamsparta.productapi.global.enums.Status;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductPlainService {

    private final ProductPlainQueryRepository productPlainQueryRepository;
    private final ProductPlainRepository productPlainRepository;

    @Transactional(readOnly = true)
    public Page<ProductPlainSummaryResponse> searchProducts(
            String keyword,
            String brandName,
            Long categoryId,
            Status status,
            Pageable pageable
    ) {
        return productPlainQueryRepository
                .searchProducts(keyword, brandName, categoryId, status, pageable)
                .map(ProductPlainSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductPlainSummaryResponse> searchProductsFts(
            String keyword,
            String brandName,
            Long categoryId,
            Status status,
            Pageable pageable
    ) {
        String tsquery = toTsQuery(keyword);
        String brandTsquery = toTsQuery(brandName);
        String statusStr = status != null ? status.name() : null;

        List<ProductPlain> content = productPlainRepository.searchByFts(
                keyword, tsquery,
                brandName, brandTsquery,
                categoryId, statusStr,
                pageable.getPageSize(), pageable.getOffset()
        );
        Long total = productPlainRepository.countByFts(
                keyword, tsquery,
                brandName, brandTsquery,
                categoryId, statusStr
        );
        return new PageImpl<>(content, pageable, total != null ? total : 0)
                .map(ProductPlainSummaryResponse::from);
    }

    private String toTsQuery(String keyword) {
        if (!StringUtils.hasText(keyword)) return null;
        return keyword.trim().replaceAll("\\s+", " & ");
    }
}