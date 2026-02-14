package org.teamsparta.productapi.domain.product.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;
import org.teamsparta.productapi.domain.product.event.dto.InventoryCreateResult;
import org.teamsparta.productapi.domain.product.repository.ProductVariantRepository;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductVariantService {

    private final ProductVariantRepository productVariantRepository;

    // TODO: Variant만 따로 추가 및 업데이트 로직 생성
    @Transactional
    public void activeProductVariant(InventoryCreateResult event){
        ProductVariant productVariant = productVariantRepository.findBySku(event.sku())
                .orElseThrow(()->new DomainException(DomainExceptionCode.NOT_FOUND_VARIANT));

        if (productVariant.getStatus() == Status.ACTIVE) {
            log.info("SKU: {} is already ACTIVE. Skipping.", event.sku());
            return;
        }

        productVariant.activeStatus();
        productVariantRepository.save(productVariant);
    }
}
