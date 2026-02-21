package org.teamsparta.inventoryapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryReservationExpiredEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationItemRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryExpireService;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class InventoryExpireServiceTest {

    @InjectMocks
    private InventoryExpireService inventoryExpireService;

    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private InventoryReservationItemRepository inventoryReservationItemRepository;
    @Mock
    private InventoryStockRepository inventoryStockRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ObjectMapper objectMapper;

    private final UUID RESERVATION_ID = UUID.randomUUID();
    private final String SKU = "SKU-001";

    @Test
    @DisplayName("성공 - 만료된 예약을 처리하면 재고가 복구되고 상태가 EXPIRED")
    void expireOne_success()throws Exception {
        // given
        InventoryReservation reservation = InventoryReservation.create(1L, UUID.randomUUID(), ReservationStatus.RESERVED, null);
        ReflectionTestUtils.setField(reservation, "id", RESERVATION_ID);
        InventoryReservationItem item = InventoryReservationItem.create(RESERVATION_ID, SKU, 5);
        InventoryStock stock = InventoryStock.create(SKU, 10);
        stock.increaseReserved(5);

        given(inventoryReservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(reservation));
        given(inventoryReservationItemRepository.findAllByReservationId(RESERVATION_ID)).willReturn(List.of(item));
        given(inventoryStockRepository.findAllSkuInForUpdate(anyList())).willReturn(List.of(stock));
        given(objectMapper.writeValueAsString(any(InventoryReservationExpiredEvent.class)))
                .willReturn("{\"orderId\":1}");

        // when
        inventoryExpireService.expireOne(RESERVATION_ID);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(stock.getReservedQuantity()).isEqualTo(0);

        // Outbox 이벤트 발행 확인
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("무시 - 이미 RESERVED 상태가 아닌 예약은 처리하지 않고 리턴")
    void expireOne_failure(){
        // given
        InventoryReservation confirmedRes = InventoryReservation.create(1L, UUID.randomUUID(), ReservationStatus.CONFIRMED, null);
        given(inventoryReservationRepository.findByIdForUpdate(RESERVATION_ID)).willReturn(Optional.of(confirmedRes));

        // when
        inventoryExpireService.expireOne(RESERVATION_ID);

        // then
        verify(inventoryStockRepository, never()).findAllSkuInForUpdate(anyList());
        verify(outboxEventRepository, never()).save(any());
    }
}
