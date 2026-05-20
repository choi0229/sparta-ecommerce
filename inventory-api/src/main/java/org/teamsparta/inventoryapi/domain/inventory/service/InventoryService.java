package org.teamsparta.inventoryapi.domain.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.*;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderConfirmResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.VariantCreatResult;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationItemRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final InventoryStockRepository inventoryStockRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryReservationItemRepository inventoryReservationItemRepository;
    private final InventoryEventPublisher inventoryEventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void createInventory(VariantCreatResult request){
        Optional<InventoryStock> existingStock = inventoryStockRepository.findById(request.sku());

        if (existingStock.isPresent()) {
            log.info("SKU {} already exists. No action taken.", request.sku());
            return;
        }
        inventoryStockRepository.save(InventoryStock.create(request.sku(), request.totalQuantity()));

        InventoryCreatedEvent inventoryCreatedEvent = InventoryCreatedEvent.from(request.sku());
        String payload;
        try{
            payload = objectMapper.writeValueAsString(inventoryCreatedEvent);
            outboxEventRepository.save(OutboxEvent.pending("Inventory", request.sku(), "inventory-created-event", payload));
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }

    @Transactional
    public void reserveInventory(OrderCreateResult request) {
        log.info("Starting inventory reservation for Order: {}", request.orderId());
        try{
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

            // 4. 가용수령 검증
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
            InventoryReservation savedReservation = inventoryReservationRepository.save(reservation);

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

            List<InventoryReservedEvent.Item> eventItems = items.stream()
                    .map(item -> new InventoryReservedEvent.Item(item.getSku(), item.getQuantity()))
                    .toList();
            InventoryReservedEvent event = InventoryReservedEvent.from(savedReservation.getOrderId(), savedReservation.getSagaId(), savedReservation.getId(), expiresAt, eventItems);

            String payload;
            try{
                payload = objectMapper.writeValueAsString(event);
            }catch(Exception ex){
                throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
            }

            outboxEventRepository.save(OutboxEvent.pending("Inventory", event.getOrderId().toString(), "inventory-reserved-event", payload));
        }catch (Exception e){
            InventoryReserveFailedEvent failedEvent = InventoryReserveFailedEvent.from(request.sagaId(), request.orderId(), e.getMessage());
            // inventoryEventPublisher.publisherInventoryReservedFailed(failedEvent);
            String payload;
            try{
                payload = objectMapper.writeValueAsString(failedEvent);
            }catch(Exception ex){
                throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
            }
            outboxEventRepository.save(OutboxEvent.pending("Inventory", failedEvent.getOrderId().toString(), "inventory-failed-event", payload));
        }
    }

    @Transactional
    public void onInventoryConfirmed(OrderConfirmResult event){
        // 1. 예약 정보 조회 (이미 확정되었는지 확인하여 멱등성 보장)
        InventoryReservation reservation = inventoryReservationRepository.findById(event.reservationId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.RESERVATION_NOT_FOUND));

        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Idempotent: Reservation already confirmed. reservationId={}", event.reservationId());
            return;
        }

        List<InventoryReservationItem> items = inventoryReservationItemRepository.findAllByReservationId(event.reservationId());
        List<String> skus = items.stream().map(InventoryReservationItem::getSku).sorted().toList();

        List<InventoryStock> stocks = inventoryStockRepository.findAllSkuInForUpdate(skus);
        Map<String, InventoryStock> stockMap = stocks.stream().collect(Collectors.toMap(InventoryStock::getSku, s -> s));

        for (InventoryReservationItem item : items) {
            InventoryStock stock = stockMap.get(item.getSku());
            stock.decreaseReserved(item.getQuantity());
        }

        // 3. 예약 상태 변경 및 결과 이벤트 발행
        reservation.updateStatus(ReservationStatus.CONFIRMED);
        InventoryConfirmedEvent inventoryConfirmedEvent = InventoryConfirmedEvent.from(event.orderId(), event.sagaId());

        String payload;
        try{
            payload = objectMapper.writeValueAsString(inventoryConfirmedEvent);
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }

        outboxEventRepository.save(OutboxEvent.pending("Inventory", inventoryConfirmedEvent.getOrderId().toString(), "inventory-confirm-event", payload));
    }
}
