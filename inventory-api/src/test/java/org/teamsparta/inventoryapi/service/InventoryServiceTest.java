package org.teamsparta.inventoryapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryConfirmedEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryCreatedEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryReserveFailedEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryReservedEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderConfirmResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.VariantCreatResult;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationItemRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryService;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class InventoryServiceTest {

    @InjectMocks
    private InventoryService inventoryService;

    @Mock
    private InventoryStockRepository inventoryStockRepository;
    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private InventoryReservationItemRepository inventoryReservationItemRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private ObjectMapper objectMapper;

    private final String SKU = "SKU-TEST-123";

    @Test
    @DisplayName("재고 생성 - 신규 SKU면 재고 생성 + outbox(inventory-created-event) 저장")
    void createInventory_success() throws Exception {
        // given
        VariantCreatResult req = new VariantCreatResult(UUID.randomUUID(), "", SKU, 10);
        given(inventoryStockRepository.findById(SKU)).willReturn(Optional.empty());
        given(objectMapper.writeValueAsString(any(InventoryCreatedEvent.class))).willReturn("{\"ok\":true}");

        // when
        inventoryService.createInventory(req);

        // then
        then(inventoryStockRepository).should(times(1)).save(any(InventoryStock.class));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(1)).save(captor.capture());

        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getAggregateType()).isEqualTo("Inventory");
        assertThat(outbox.getAggregateId()).isEqualTo(SKU);
        assertThat(outbox.getEventType()).isEqualTo("inventory-created-event");
        assertThat(outbox.getPayload()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("재고 생성 저장 안함 - 이미 SKU가 있으면 stock/outbox 저장 없이 return")
    void createInventory_existingSku_noStockSave() {
        // given
        VariantCreatResult req = new VariantCreatResult(UUID.randomUUID(), "", SKU, 10);
        InventoryStock existing = InventoryStock.create(SKU, 99);
        given(inventoryStockRepository.findById(SKU)).willReturn(Optional.of(existing));

        // when
        inventoryService.createInventory(req);

        // then: SKU 중복이면 stock save 없이 return → outbox도 발행하지 않는다
        then(inventoryStockRepository).should(never()).save(any(InventoryStock.class));
        then(outboxEventRepository).should(never()).save(any(OutboxEvent.class));
    }

    @Test
    @DisplayName("reserveInventory 성공 -> reserved 증가 + reservation/item 저장 + outbox(inventory-reserved-event)")
    void reserveInventory_success() throws Exception {
        // given
        UUID sagaId = UUID.randomUUID();
        OrderCreateResult req = new OrderCreateResult(
                UUID.randomUUID(), "order.create", 100L, sagaId, 1L,
                List.of(new OrderCreateResult.Item(SKU, 2))
        );

        given(inventoryReservationRepository.findByOrderId(100L)).willReturn(Optional.empty());

        // 재고 row lock 조회 결과
        InventoryStock stock = InventoryStock.create(SKU, 10);
        // reservedQuantity 초기값 세팅 필요하면 create()가 내부에서 0으로 세팅돼야 함
        given(inventoryStockRepository.findAllSkuInForUpdate(anyList())).willReturn(List.of(stock));

        // reservation save 결과: id가 필요
        InventoryReservation reservation = InventoryReservation.create(
                100L, sagaId, ReservationStatus.RESERVED, ZonedDateTime.now(ZoneId.systemDefault()).plusMinutes(30)
        );
        UUID reservationId = UUID.randomUUID();
        ReflectionTestUtils.setField(reservation, "id", reservationId);
        given(inventoryReservationRepository.save(any(InventoryReservation.class))).willReturn(reservation);

        given(objectMapper.writeValueAsString(any(InventoryReservedEvent.class))).willReturn("{\"reserved\":true}");

        // when
        inventoryService.reserveInventory(req);

        // then
        then(inventoryStockRepository).should(times(1)).saveAll(anyList());
        then(inventoryReservationItemRepository).should(times(1)).saveAll(anyList());
        then(inventoryReservationRepository).should(times(1)).save(any(InventoryReservation.class));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(1)).save(captor.capture());
        OutboxEvent outbox = captor.getValue();

        assertThat(outbox.getAggregateType()).isEqualTo("Inventory");
        assertThat(outbox.getAggregateId()).isEqualTo("100"); // event.getOrderId().toString()
        assertThat(outbox.getEventType()).isEqualTo("inventory-reserved-event");
        assertThat(outbox.getPayload()).isEqualTo("{\"reserved\":true}");
    }

    @Test
    @DisplayName("reserveInventory - 멱등성(이미 RESERVED/CONFIRMED) -> 아무 작업도 하지 않고 return")
    void reserveInventory_idempotent_return() {
        // given
        UUID sagaId = UUID.randomUUID();
        OrderCreateResult req = new OrderCreateResult(
                UUID.randomUUID(), "order.create", 100L, sagaId, 1L,
                List.of(new OrderCreateResult.Item(SKU, 2))
        );

        InventoryReservation existing = InventoryReservation.create(
                100L, sagaId, ReservationStatus.RESERVED, ZonedDateTime.now().plusMinutes(30)
        );
        given(inventoryReservationRepository.findByOrderId(100L)).willReturn(Optional.of(existing));

        // when
        inventoryService.reserveInventory(req);

        // then
        then(inventoryStockRepository).shouldHaveNoInteractions();
        then(inventoryReservationRepository).should(times(1)).findByOrderId(100L);
        then(inventoryReservationRepository).should(never()).save(any());
        then(inventoryReservationItemRepository).should(never()).saveAll(anyList());
        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("reserveInventory - OUT_OF_STOCK 등 예외 발생 -> outbox(inventory-failed-event) 저장")
    void reserveInventory_fail_outboxFailedEvent() throws Exception {
        // given
        UUID sagaId = UUID.randomUUID();
        OrderCreateResult req = new OrderCreateResult(
                UUID.randomUUID(), "order.create", 100L, sagaId, 1L,
                List.of(new OrderCreateResult.Item(SKU, 99))
        );

        given(inventoryReservationRepository.findByOrderId(100L)).willReturn(Optional.empty());

        InventoryStock stock = InventoryStock.create(SKU, 10); // total=10
        given(inventoryStockRepository.findAllSkuInForUpdate(anyList())).willReturn(List.of(stock));

        // 실패 이벤트 직렬화
        given(objectMapper.writeValueAsString(any(InventoryReserveFailedEvent.class))).willReturn("{\"failed\":true}");

        // when
        inventoryService.reserveInventory(req);

        // then: 성공쪽 저장들은 호출되면 안 됨
        then(inventoryReservationRepository).should(never()).save(any());
        then(inventoryReservationItemRepository).should(never()).saveAll(anyList());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(1)).save(captor.capture());

        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo("inventory-failed-event");
        assertThat(outbox.getAggregateId()).isEqualTo("100");
        assertThat(outbox.getPayload()).isEqualTo("{\"failed\":true}");
    }

    @Test
    @DisplayName("onInventoryConfirmed 성공 -> reserved 감소 + reservation CONFIRMED + outbox(inventory-confirm-event)")
    void onInventoryConfirmed_success() throws Exception {
        // given
        UUID sagaId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Long orderId = 100L;

        OrderConfirmResult req = new OrderConfirmResult(
                UUID.randomUUID(), "order.confirm", orderId, sagaId, reservationId
        );

        InventoryReservation reservation = InventoryReservation.create(
                orderId, sagaId, ReservationStatus.RESERVED, ZonedDateTime.now().plusMinutes(30)
        );
        ReflectionTestUtils.setField(reservation, "id", reservationId);

        given(inventoryReservationRepository.findById(reservationId)).willReturn(Optional.of(reservation));

        InventoryReservationItem item = InventoryReservationItem.create(reservationId, SKU, 2);
        given(inventoryReservationItemRepository.findAllByReservationId(reservationId)).willReturn(List.of(item));

        InventoryStock stock = InventoryStock.create(SKU, 10);
        // reserve 상태를 만들어두기 (reservedQuantity=2)
        stock.increaseReserved(2);

        given(inventoryStockRepository.findAllSkuInForUpdate(anyList())).willReturn(List.of(stock));

        given(objectMapper.writeValueAsString(any(InventoryConfirmedEvent.class))).willReturn("{\"confirmed\":true}");

        // when
        inventoryService.onInventoryConfirmed(req);

        // then
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        // reserved 감소 검증 (2 -> 0)
        assertThat(stock.getReservedQuantity()).isEqualTo(0);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should(times(1)).save(captor.capture());

        OutboxEvent outbox = captor.getValue();
        assertThat(outbox.getEventType()).isEqualTo("inventory-confirm-event");
        assertThat(outbox.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(outbox.getPayload()).isEqualTo("{\"confirmed\":true}");
    }

}
