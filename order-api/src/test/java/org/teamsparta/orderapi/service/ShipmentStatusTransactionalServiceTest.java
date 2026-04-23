package org.teamsparta.orderapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.service.ShipmentStatusTransactionalService;
import org.teamsparta.orderapi.global.enums.ShipmentStatus;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentStatusTransactionalServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock IdempotencyRepository idempotencyRepository;
    @InjectMocks ShipmentStatusTransactionalService service;

    private static final String IDEM_KEY = "shipment-event:evt-001";

    private ShipmentEventPayload shippedPayload() {
        return new ShipmentEventPayload("evt-001", 1L, 10L, "SHIPPED", "");
    }

    private Orders orderWithId(Long id) {
        Orders order = Orders.createNew("O001", 42L);
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    @DisplayName("정상 처리 — shipmentStatus 업데이트 후 idem COMPLETED 저장")
    void applyShipmentStatus_happyPath_updatesStatusAndCompletesIdem() {
        Orders order = orderWithId(10L);
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.empty());
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        service.applyShipmentStatus(IDEM_KEY, shippedPayload());

        assertThat(order.getShipmentStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        then(orderRepository).should(times(1)).save(order);
        then(idempotencyRepository).should(times(2)).save(any(IdempotencyRecord.class));
    }

    @Test
    @DisplayName("중복 idemKey — 즉시 반환, orderRepository 미호출")
    void applyShipmentStatus_duplicateIdemKey_skipsProcessing() {
        IdempotencyRecord existing = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(existing));

        service.applyShipmentStatus(IDEM_KEY, shippedPayload());

        then(orderRepository).shouldHaveNoInteractions();
        then(idempotencyRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("orderId 미존재 — DomainException(NOT_FOUND_ORDER) 발생")
    void applyShipmentStatus_orderNotFound_throwsDomainException() {
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.empty());
        given(orderRepository.findById(10L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyShipmentStatus(IDEM_KEY, shippedPayload()))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.NOT_FOUND_ORDER.name()));
    }
}
