package org.teamsparta.logisticsapi.domain.logistics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentAddressUpdateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCancelRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentStatusUpdateRequest;
import java.util.UUID;
import org.teamsparta.logisticsapi.domain.logistics.dto.response.ShipmentResponse;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.domain.logistics.repository.ShipmentRepository;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogisticsService {

    private final ShipmentRepository shipmentRepository;
    private final LogisticsTransactionalService transactionalService;

    public ShipmentResponse createShipment(ShipmentCreateRequest request) {
        if (shipmentRepository.existsByOrderId(request.orderId())) {
            throw new DomainException(DomainExceptionCode.DUPLICATE_SHIPMENT);
        }
        Shipment shipment = transactionalService.createShipment(request);
        log.info("Shipment created. shipmentId={}, orderId={}", shipment.getId(), shipment.getOrderId());
        return ShipmentResponse.from(shipment);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getShipment(Long shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.SHIPMENT_NOT_FOUND));
        return ShipmentResponse.from(shipment);
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getShipmentByOrderId(Long orderId) {
        Shipment shipment = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.SHIPMENT_NOT_FOUND));
        return ShipmentResponse.from(shipment);
    }

    public ShipmentResponse updateStatus(Long shipmentId, ShipmentStatusUpdateRequest request) {
        Shipment shipment = transactionalService.updateStatus(
                shipmentId, request.status(), request.description(), UUID.randomUUID().toString()
        );
        log.info("Shipment status updated. shipmentId={}, status={}", shipment.getId(), shipment.getStatus());
        return ShipmentResponse.from(shipment);
    }

    /**
     * 배송 취소 API 진입점.
     * - 이미 CANCELED인 경우: history/outbox를 새로 생성하지 않고 현재 상태를 그대로 반환 (멱등)
     * - READY가 아닌 경우: SHIPMENT_CANCEL_NOT_ALLOWED 예외 (트랜잭션 진입 전 조기 차단)
     * - READY인 경우: LogisticsTransactionalService에 위임해 단일 트랜잭션으로 처리
     */
    public ShipmentResponse cancelShipment(Long shipmentId, ShipmentCancelRequest request) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.SHIPMENT_NOT_FOUND));

        if (shipment.getStatus() == ShipmentStatus.CANCELED) {
            log.info("Shipment already CANCELED (idempotent return). shipmentId={}", shipmentId);
            return ShipmentResponse.from(shipment);
        }

        if (shipment.getStatus() != ShipmentStatus.READY) {
            throw new DomainException(DomainExceptionCode.SHIPMENT_CANCEL_NOT_ALLOWED);
        }

        Shipment canceled = transactionalService.cancelShipment(shipmentId, request.cancelReason());
        log.info("Shipment canceled. shipmentId={}, orderId={}", canceled.getId(), canceled.getOrderId());
        return ShipmentResponse.from(canceled);
    }

    public ShipmentResponse updateAddress(Long shipmentId, ShipmentAddressUpdateRequest request) {
        Shipment shipment = transactionalService.updateAddress(
                shipmentId, request.recipientName(), request.recipientAddress()
        );
        log.info("Shipment address updated. shipmentId={}", shipmentId);
        return ShipmentResponse.from(shipment);
    }
}
