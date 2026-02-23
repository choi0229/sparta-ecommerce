package org.teamsparta.productapi.domain.product.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.entity.Product;
import org.teamsparta.productapi.domain.product.entity.ProductVariant;
import org.teamsparta.productapi.domain.product.event.ProductSnapShotReplyEvent;
import org.teamsparta.productapi.domain.product.event.dto.InventoryCreateResult;
import org.teamsparta.productapi.domain.product.event.dto.ProductSnapshotRequestResult;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.ProductVariantRepository;
import org.teamsparta.productapi.global.enums.Status;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductVariantService {

    private final ProductVariantRepository productVariantRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

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

    @Transactional
    public void replyProductSnapshot(ProductSnapshotRequestResult event){
        List<String> skus = event.skus();
        if(skus == null || skus.isEmpty()){
            saveReplyOutbox(ProductSnapShotReplyEvent.error(event.requestId(), "EMPTY_SKUS"));
            return;
        }

        List<ProductVariant> variants = productVariantRepository.findBySkuIn(skus);
        Map<String, ProductVariant> bySku = variants.stream()
                .collect(Collectors.toMap(ProductVariant::getSku, v -> v));

        List<String> missing = skus.stream()
                .filter(s -> !bySku.containsKey(s))
                .distinct()
                .toList();

        if(!missing.isEmpty()){
            saveReplyOutbox(ProductSnapShotReplyEvent.error(event.requestId(), "MISSING_SKU="+missing));
            return;
        }

        List<ProductSnapShotReplyEvent.ProductSnapshotItem> items = skus.stream().map(sku -> {
            ProductVariant variant = bySku.get(sku);
            return ProductSnapShotReplyEvent.ProductSnapshotItem.builder()
                    .sku(variant.getSku())
                    .variantId(variant.getId())
                    .productName(variant.getProduct().getName())
                    .productId(variant.getProduct().getId())
                    .price(variant.getPrice())
                    .optionJson(variant.getOptionJson())
                    .build();

        }).toList();

        saveReplyOutbox(ProductSnapShotReplyEvent.ok(event.requestId(), items));
    }

    private void saveReplyOutbox(ProductSnapShotReplyEvent event){
        try{
            String payload = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.pending("ProductSnapshot", event.getRequestId().toString(), "productSnapshot-reply-event", payload));
        }catch (Exception ex){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }
}
