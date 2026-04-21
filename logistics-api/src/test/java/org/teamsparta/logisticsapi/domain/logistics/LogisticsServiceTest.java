package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentStatusUpdateRequest;
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
}
