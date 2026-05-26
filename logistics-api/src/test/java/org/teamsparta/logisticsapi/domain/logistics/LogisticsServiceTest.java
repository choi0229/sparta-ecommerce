package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCancelRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentStatusUpdateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.response.ShipmentResponse;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.domain.logistics.repository.ShipmentRepository;
import org.teamsparta.logisticsapi.domain.logistics.service.LogisticsService;
import org.teamsparta.logisticsapi.domain.logistics.service.LogisticsTransactionalService;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class LogisticsServiceTest {

    @Mock
    ShipmentRepository shipmentRepository;

    @Mock
    LogisticsTransactionalService transactionalService;

    @InjectMocks
    LogisticsService logisticsService;

    Shipment shipment;

    @BeforeEach
    void setUp() {
        shipment = Shipment.create(1L, "홍길동", "서울시 강남구");
    }

    @Test
    @DisplayName("배송 요청을 생성할 수 있다")
    void createShipment_success() {
        given(shipmentRepository.existsByOrderId(1L)).willReturn(false);
        given(transactionalService.createShipment(any())).willReturn(shipment);

        assertThatNoException().isThrownBy(() ->
                logisticsService.createShipment(new ShipmentCreateRequest(1L, "홍길동", "서울시 강남구")));
    }

    @Test
    @DisplayName("동일한 orderId로 중복 배송 요청이 오면 예외가 발생한다")
    void createShipment_duplicateOrderId_throws() {
        given(shipmentRepository.existsByOrderId(1L)).willReturn(true);

        assertThatThrownBy(() ->
                logisticsService.createShipment(new ShipmentCreateRequest(1L, "홍길동", "서울시 강남구")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(DomainExceptionCode.DUPLICATE_SHIPMENT.getMessage());
    }

    @Test
    @DisplayName("배송 정보를 조회할 수 있다")
    void getShipment_success() {
        given(shipmentRepository.findById(1L)).willReturn(Optional.of(shipment));

        assertThatNoException().isThrownBy(() -> logisticsService.getShipment(1L));
    }

    @Test
    @DisplayName("존재하지 않는 배송 조회 시 예외가 발생한다")
    void getShipment_notFound_throws() {
        given(shipmentRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> logisticsService.getShipment(99L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(DomainExceptionCode.SHIPMENT_NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("배송 상태를 변경할 수 있다")
    void updateStatus_success() {
        given(transactionalService.updateStatus(any(), any(), any(), any())).willReturn(shipment);

        assertThatNoException().isThrownBy(() ->
                logisticsService.updateStatus(1L,
                        new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "출고 완료")));
    }

    @Test
    @DisplayName("READY 상태의 배송을 취소할 수 있다")
    void cancelShipment_ready_success() {
        given(shipmentRepository.findById(1L)).willReturn(Optional.of(shipment));
        given(transactionalService.cancelShipment(eq(1L), any())).willReturn(shipment);

        assertThatNoException().isThrownBy(() ->
                logisticsService.cancelShipment(1L, new ShipmentCancelRequest("고객 요청으로 취소")));
        then(transactionalService).should(times(1)).cancelShipment(eq(1L), eq("고객 요청으로 취소"));
    }

    @Test
    @DisplayName("이미 CANCELED 상태이면 예외 없이 현재 상태를 반환하고 transactionalService를 호출하지 않는다")
    void cancelShipment_alreadyCanceled_idempotentReturn() {
        shipment.changeStatus(ShipmentStatus.CANCELED);
        given(shipmentRepository.findById(1L)).willReturn(Optional.of(shipment));

        ShipmentResponse response = logisticsService.cancelShipment(1L, new ShipmentCancelRequest("고객 요청"));

        assertThat(response.status()).isEqualTo(ShipmentStatus.CANCELED);
        then(transactionalService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("READY가 아닌 상태(SHIPPED)에서 취소 시 SHIPMENT_CANCEL_NOT_ALLOWED 예외가 발생하고 transactionalService를 호출하지 않는다")
    void cancelShipment_notReady_throwsCancelNotAllowed() {
        shipment.changeStatus(ShipmentStatus.SHIPPED);
        given(shipmentRepository.findById(1L)).willReturn(Optional.of(shipment));

        assertThatThrownBy(() ->
                logisticsService.cancelShipment(1L, new ShipmentCancelRequest("취소 이유")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(DomainExceptionCode.SHIPMENT_CANCEL_NOT_ALLOWED.getMessage());
        then(transactionalService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("updateStatus 호출 시 transactionalService에 non-null UUID eventId가 전달된다")
    void updateStatus_passesNonNullUuidEventId() {
        given(transactionalService.updateStatus(any(), any(), any(), any())).willReturn(shipment);
        ArgumentCaptor<String> eventIdCaptor = ArgumentCaptor.forClass(String.class);

        logisticsService.updateStatus(1L,
                new ShipmentStatusUpdateRequest(ShipmentStatus.SHIPPED, "출고 완료"));

        then(transactionalService).should().updateStatus(
                eq(1L), eq(ShipmentStatus.SHIPPED), eq("출고 완료"), eventIdCaptor.capture());
        assertThat(eventIdCaptor.getValue()).isNotNull();
        assertThatNoException().isThrownBy(() ->
                java.util.UUID.fromString(eventIdCaptor.getValue()));
    }
}
