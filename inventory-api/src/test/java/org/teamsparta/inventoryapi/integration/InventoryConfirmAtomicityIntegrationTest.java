package org.teamsparta.inventoryapi.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryConfirmedEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.InventoryReservationExpiredEvent;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderConfirmResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryExpireService;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryService;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;
import org.teamsparta.inventoryapi.global.exception.DomainException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 재고 변경 후 Outbox 직렬화에서 실패하게 만들어, 재고·예약 상태·Outbox가 함께 롤백되는지 검증한다.
 */
class InventoryConfirmAtomicityIntegrationTest extends InventoryIntegrationTestSupport {

    private static final String SKU = "SKU-ATOMIC";
    private static final long ORDER_ID = 777L;

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InventoryExpireService inventoryExpireService;
    @Autowired
    private InventoryStockRepository inventoryStockRepository;
    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private ObjectMapper objectMapper;
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM inventory_stock");
    }

    @Test
    @DisplayName("확정 중 실패 → 재고와 예약 상태가 함께 롤백되고 Outbox도 남지 않는다")
    void confirm_failureAfterStockChange_rollsBackEverything() throws Exception {
        InventoryReservation reservation = reserve();
        doThrow(new JsonProcessingException("serialization failed") { })
                .when(objectMapper).writeValueAsString(any(InventoryConfirmedEvent.class));

        assertThatThrownBy(() -> inventoryService.onInventoryConfirmed(new OrderConfirmResult(
                UUID.randomUUID(), "order.confirm", ORDER_ID, reservation.getSagaId(), reservation.getId())))
                .isInstanceOf(DomainException.class);

        assertUnchanged(reservation, "inventory-confirm-event");
    }

    @Test
    @DisplayName("만료 중 실패 → 재고와 예약 상태가 함께 롤백되고 Outbox도 남지 않는다")
    void expire_failureAfterStockChange_rollsBackEverything() throws Exception {
        InventoryReservation reservation = reserve();
        doThrow(new JsonProcessingException("serialization failed") { })
                .when(objectMapper).writeValueAsString(any(InventoryReservationExpiredEvent.class));

        assertThatThrownBy(() -> inventoryExpireService.expireOne(reservation.getId()))
                .isInstanceOf(DomainException.class);

        assertUnchanged(reservation, "inventory-expired-event");
    }

    private InventoryReservation reserve() {
        inventoryStockRepository.save(InventoryStock.create(SKU, 10));
        inventoryService.reserveInventory(new OrderCreateResult(
                UUID.randomUUID(), "order.created", ORDER_ID, UUID.randomUUID(), 1L,
                List.of(new OrderCreateResult.Item(SKU, 4))));
        return inventoryReservationRepository.findByOrderId(ORDER_ID).orElseThrow();
    }

    private void assertUnchanged(InventoryReservation reservation, String eventType) {
        InventoryStock stock = inventoryStockRepository.findById(SKU).orElseThrow();
        assertThat(stock.getTotalQuantity()).isEqualTo(10);
        assertThat(stock.getReservedQuantity()).isEqualTo(4);
        assertThat(inventoryReservationRepository.findById(reservation.getId()).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Long.class, eventType)).isZero();
    }
}
