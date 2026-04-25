package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.event.dto.ShipmentEventPayload;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;
import org.teamsparta.orderapi.global.enums.ShipmentStatus;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentStatusTransactionalService {

    private final OrderRepository orderRepository;
    private final IdempotencyRepository idempotencyRepository;

    @Transactional
    public void applyShipmentStatus(String idemKey, ShipmentEventPayload payload) {
        log.info("Applying shipment status. idemKey={}, orderId={}, status={}",
                idemKey, payload.orderId(), payload.status());

        IdempotencyRecord record = idempotencyRepository.findById(idemKey).orElse(null);

        if (record != null && IdempotencyStatus.COMPLETED.equals(record.getStatus())) {
            log.info("Duplicate shipment-event skipped. idemKey={}", idemKey);
            return;
        }

        ShipmentStatus targetStatus = ShipmentStatus.valueOf(payload.status());
        Orders order = orderRepository.findById(payload.orderId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        if (record != null) {
            // PENDING record exists — recover if status already applied, otherwise reuse record
            if (targetStatus.equals(order.getShipmentStatus())) {
                record.complete(order.getId());
                idempotencyRepository.save(record);
                log.info("Recovered stuck PENDING record. idemKey={}", idemKey);
                return;
            }
        } else {
            record = IdempotencyRecord.start(idemKey, idemKey);
            idempotencyRepository.save(record);
        }

        order.updateShipmentStatus(targetStatus);
        orderRepository.save(order);

        record.complete(order.getId());
        idempotencyRepository.save(record);

        log.info("ShipmentStatus updated. idemKey={}, orderId={}, status={}", idemKey, order.getId(), targetStatus);
    }
}
