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

    public ShipmentResponse cancelShipment(Long shipmentId, ShipmentCancelRequest request) {
        Shipment shipment = transactionalService.cancelShipment(
                shipmentId, request.cancelReason(), UUID.randomUUID().toString());
        log.info("Shipment cancel processed. shipmentId={}, status={}", shipmentId, shipment.getStatus());
        return ShipmentResponse.from(shipment);
    }

    public ShipmentResponse updateAddress(Long shipmentId, ShipmentAddressUpdateRequest request) {
        Shipment shipment = transactionalService.updateAddress(
                shipmentId, request.recipientName(), request.recipientAddress()
        );
        log.info("Shipment address updated. shipmentId={}", shipmentId);
        return ShipmentResponse.from(shipment);
    }
}
