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
        if (idempotencyRepository.findById(idemKey).isPresent()) {
            log.info("Duplicate shipment-event skipped. idemKey={}", idemKey);
            return;
        }

        IdempotencyRecord record = IdempotencyRecord.start(idemKey, idemKey);
        idempotencyRepository.save(record);

        Orders order = orderRepository.findById(payload.orderId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        order.updateShipmentStatus(ShipmentStatus.valueOf(payload.status()));
        orderRepository.save(order);

        record.complete(order.getId());
        idempotencyRepository.save(record);
    }
}
