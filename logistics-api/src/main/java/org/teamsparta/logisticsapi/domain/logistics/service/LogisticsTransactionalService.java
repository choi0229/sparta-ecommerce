package org.teamsparta.logisticsapi.domain.logistics.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.entity.IdempotencyRecord;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.domain.logistics.entity.ShipmentStatusHistory;
import org.teamsparta.logisticsapi.domain.logistics.repository.IdempotencyRecordRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.ShipmentRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.ShipmentStatusHistoryRepository;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LogisticsTransactionalService {

    private final ShipmentRepository shipmentRepository;
    private final ShipmentStatusHistoryRepository historyRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final ObjectMapper objectMapper;

    /**
     * Kafka order-event 소비 경로 전용. eventId 기반 멱등성 보장.
     * IdempotencyRecord 저장 → Shipment 생성 → 완료 처리를 단일 트랜잭션으로 수행한다.
     *
     * PENDING 레코드 복구 기준:
     * - PENDING + 배송 존재: 이전 시도가 배송 생성까지 성공했으나 complete() 전에 중단됨 → 레코드만 완료 처리
     * - PENDING + 배송 없음: 이전 시도가 PENDING 저장 후 배송 생성 전에 중단됨 → 배송 생성부터 재시도
     */
    @Transactional
    public Shipment createShipmentForOrderEvent(String idemKey, ShipmentCreateRequest request) {
        IdempotencyRecord record = idempotencyRecordRepository.findById(idemKey).orElse(null);

        if (record != null && record.isAlreadyProcessed()) {
            log.info("Duplicate order-event skipped. idemKey={}", idemKey);
            return null;
        }

        if (record != null) {
            // PENDING 레코드가 존재하는 경우: 이전 시도가 중간에 중단된 상황
            if (shipmentRepository.existsByOrderId(request.orderId())) {
                // 배송이 이미 생성됨 → idempotency 레코드만 완료 처리 후 반환
                record.complete();
                idempotencyRecordRepository.save(record);
                log.info("Recovered stuck PENDING record. idemKey={}", idemKey);
                return shipmentRepository.findByOrderId(request.orderId()).orElse(null);
            }
            // 배송이 없음 → 아래에서 배송 생성 진행 (기존 PENDING 레코드 재사용)
        } else {
            record = IdempotencyRecord.start(idemKey);
            idempotencyRecordRepository.save(record);
        }

        Shipment shipment = Shipment.create(
                request.orderId(), request.recipientName(), request.recipientAddress());
        shipmentRepository.save(shipment);

        historyRepository.save(ShipmentStatusHistory.record(
                shipment.getId(), ShipmentStatus.READY, "배송 요청 생성", null));

        outboxEventRepository.save(OutboxEvent.pending(
                "shipment", String.valueOf(shipment.getId()),
                "shipment-created-event", toPayload(shipment, null)));

        record.complete();
        idempotencyRecordRepository.save(record);

        return shipment;
    }

    @Transactional
    public Shipment createShipment(ShipmentCreateRequest request) {
        Shipment shipment = Shipment.create(
                request.orderId(),
                request.recipientName(),
                request.recipientAddress()
        );
        shipmentRepository.save(shipment);

        historyRepository.save(ShipmentStatusHistory.record(
                shipment.getId(), ShipmentStatus.READY, "배송 요청 생성", null
        ));

        outboxEventRepository.save(OutboxEvent.pending(
                "shipment",
                String.valueOf(shipment.getId()),
                "shipment-created-event",
                toPayload(shipment, null)
        ));

        return shipment;
    }

    @Transactional
    public Shipment updateStatus(Long shipmentId, ShipmentStatus nextStatus,
                                 String description, String eventId) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.SHIPMENT_NOT_FOUND));

        shipment.changeStatus(nextStatus);
        shipmentRepository.save(shipment);

        historyRepository.save(ShipmentStatusHistory.record(
                shipment.getId(), nextStatus, description, eventId
        ));

        outboxEventRepository.save(OutboxEvent.pending(
                "shipment",
                String.valueOf(shipment.getId()),
                "shipment-status-changed-event",
                toPayload(shipment, description)
        ));

        return shipment;
    }

    private String toPayload(Shipment shipment, String description) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "shipmentId", shipment.getId(),
                    "orderId", shipment.getOrderId(),
                    "status", shipment.getStatus().name(),
                    "description", description != null ? description : ""
            ));
        } catch (JsonProcessingException e) {
            throw new DomainException(DomainExceptionCode.JSON_PROCESSING_ERROR);
        }
    }
}
