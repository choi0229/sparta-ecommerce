package org.teamsparta.inventoryapi.domain.inventory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryReservationExpiredEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationItemRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryExpireService {

    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryReservationItemRepository inventoryReservationItemRepository;
    private final InventoryStockRepository inventoryStockRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireOne(UUID reservationId) {
        InventoryReservation reservation = inventoryReservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.RESERVATION_NOT_FOUND));

        if(reservation.getStatus() != ReservationStatus.RESERVED){
            return;
        }

        List<InventoryReservationItem> items = inventoryReservationItemRepository.findAllByReservationId(reservationId);

        List<String> skus = items.stream()
                .map(InventoryReservationItem::getSku)
                .sorted()
                .toList();

        List<InventoryStock> stocks = inventoryStockRepository.findAllSkuInForUpdate(skus);
        Map<String, InventoryStock> stockBySku = stocks.stream()
                .collect(Collectors.toMap(InventoryStock::getSku, s -> s));

        for(InventoryReservationItem item : items){
            InventoryStock stock = stockBySku.get(item.getSku());
            if(stock == null){
                throw new DomainException(DomainExceptionCode.STOCK_NOT_FOUND);
            }
            stock.decreaseReserved(item.getQuantity());
        }

        reservation.updateStatus(ReservationStatus.EXPIRED);

        InventoryReservationExpiredEvent event = InventoryReservationExpiredEvent.from(reservation.getOrderId(), reservation.getSagaId(), reservationId);

        try{
            String payload = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.pending("Inventory", reservation.getId().toString(), "inventory-expired-event", payload));
        }catch (Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
        log.info("Reservation expired. reservationId={}. orderId={}, sagaId={}", reservationId, reservation.getOrderId(), reservation.getSagaId());

    }
}
