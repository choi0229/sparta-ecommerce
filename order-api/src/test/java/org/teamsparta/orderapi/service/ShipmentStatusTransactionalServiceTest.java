package org.teamsparta.orderapi.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.service.ShipmentStatusTransactionalService;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;
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
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();
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
    @DisplayName("COMPLETED idemKey — 즉시 반환, orderRepository 미호출")
    void applyShipmentStatus_completedIdemKey_skipsProcessing() {
        IdempotencyRecord existing = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        existing.complete(10L);
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

    @Test
    @DisplayName("PENDING + 이미 적용된 상태 — idem COMPLETED만 저장, orderRepository.save 미호출")
    void applyShipmentStatus_pendingRecord_statusAlreadyApplied_recoversIdemOnly() {
        Orders order = orderWithId(10L);
        order.updateShipmentStatus(ShipmentStatus.SHIPPED);

        IdempotencyRecord pending = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(pending));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        service.applyShipmentStatus(IDEM_KEY, shippedPayload());

        then(orderRepository).should(never()).save(any());
        then(idempotencyRepository).should(times(1)).save(pending);
        assertThat(pending.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }

    @Test
    @DisplayName("PENDING + 미적용 상태 — 기존 record 재사용하여 정상 업데이트")
    void applyShipmentStatus_pendingRecord_statusNotApplied_proceedsWithUpdate() {
        Orders order = orderWithId(10L);

        IdempotencyRecord pending = IdempotencyRecord.start(IDEM_KEY, IDEM_KEY);
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(pending));
        given(orderRepository.findById(10L)).willReturn(Optional.of(order));

        service.applyShipmentStatus(IDEM_KEY, shippedPayload());

        assertThat(order.getShipmentStatus()).isEqualTo(ShipmentStatus.SHIPPED);
        then(orderRepository).should(times(1)).save(order);
        // new PENDING save는 없고 COMPLETED save만 1회 (record 재사용)
        then(idempotencyRepository).should(times(1)).save(pending);
        assertThat(pending.getStatus()).isEqualTo(IdempotencyStatus.COMPLETED);
    }
}
