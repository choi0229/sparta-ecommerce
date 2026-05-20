package org.teamsparta.orderapi.domain.productProjection.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;
import org.teamsparta.orderapi.domain.productProjection.event.dto.ProductVariantResult;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProductProjectionService {

    private final ProductProjectionRepository productProjectionRepository;

    @Transactional
    public void updateProductProjection(ProductVariantResult request) {
        productProjectionRepository.findByVariantId(request.variantId())
                .ifPresentOrElse(
                        existing -> {
                            existing.update(request);
                        },
                        () -> {
                            try{
                                productProjectionRepository.save(ProductProjection.from(request));
                            }catch(Exception e){
                                log.warn("중복 insert 감지. 재조회 후 업데이트: variant_id={}", request.sku());
                                ProductProjection existing = productProjectionRepository.findByVariantId(request.variantId())
                                        .orElseThrow(() -> new DomainException(DomainExceptionCode.CONCURRENT_DATA_CONFLICT));
                                existing.update(request);
                            }
                        }
                );

    }
}
