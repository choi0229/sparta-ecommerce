package org.teamsparta.inventoryapi.domain.inventory.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationItemRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryStockRepository inventoryStockRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryReservationItemRepository inventoryReservationItemRepository;

    @Transactional
    public void reserveInventory(OrderCreateResult request) {
        log.info("Starting inventory reservation for Order: {}", request.orderId());

        // 1. 멱등성
        var existingOpt = inventoryReservationRepository.findByOrderId(request.orderId());
        if (existingOpt.isPresent()) {
            var existing = existingOpt.get();
            if (existing.getStatus() == ReservationStatus.RESERVED || existing.getStatus() == ReservationStatus.CONFIRMED) {
                log.info("Idempotent: reservation already exists. orderId={}, status={}", request.orderId(), existing.getStatus());
                return;
            }
            throw new DomainException(DomainExceptionCode.RESERVATION_ALREADY_TERMINATED);
        }

        // 2. SKU집계
        Map<String, Integer> quantityBySku = request.items().stream()
                .collect(Collectors.groupingBy(
                        item -> item.sku().trim(),
                        Collectors.summingInt(item -> item.quantity())
                ));

        // 수량검증
        for(Map.Entry<String, Integer> sku : quantityBySku.entrySet()){
            if(sku.getKey() == null || sku.getKey().isBlank()){
                throw new DomainException(DomainExceptionCode.INVALID_SKU);
            }
            if(sku.getValue() <= 0){
                throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
            }
        }
        List<String> skus = new ArrayList<>(quantityBySku.keySet());
        List<String> sortedSkus = skus.stream().sorted().toList(); // 데드락 방지를 위한 정렬
        List<InventoryStock> stocks = inventoryStockRepository.findAllSkuInForUpdate(sortedSkus);

        Map<String, InventoryStock> stockBySku = stocks.stream()
                .collect(Collectors.toMap(InventoryStock::getSku, s -> s));

        List<String> missing = skus.stream().filter(s -> !stockBySku.containsKey(s)).toList();
        if (!missing.isEmpty()) {
            throw new DomainException(DomainExceptionCode.STOCK_NOT_FOUND);
        }

        // 4. 가용슈령 곰중
        for (String sku : skus){
            InventoryStock stock = stockBySku.get(sku);
            Integer req = quantityBySku.get(sku);
            Integer available = stock.getTotalQuantity() - stock.getReservedQuantity();
            if(available < req){
                throw new DomainException(DomainExceptionCode.OUT_OF_STOCK);
            }
        }

        // 5. reservation 생성
        Instant instant = Instant.now().plus(Duration.ofMinutes(30));
        ZonedDateTime expiresAt = instant.atZone(ZoneId.systemDefault());
        InventoryReservation reservation = InventoryReservation.create(request.orderId(), request.sagaId(), ReservationStatus.RESERVED, expiresAt);
        inventoryReservationRepository.save(reservation);

        // 6 reserved 증가 + item insert
        List<InventoryReservationItem> items = new ArrayList<>();
        for(String sku : skus){
            Integer req = quantityBySku.get(sku);
            InventoryStock stock = stockBySku.get(sku);
            stock.increaseReserved(req);

            items.add(InventoryReservationItem.create(reservation.getId(), sku, req));
        }

        inventoryStockRepository.saveAll(stocks);
        inventoryReservationItemRepository.saveAll(items);
    }
}
