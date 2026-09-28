package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.Optional;

@Service
@RequiredArgsConstructor

public class OrderStatusQueryService {

    private final IdempotencyRepository idempotencyRepository;
    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public OrderStatusResponse getOrderStatus(String idemKey) {
        Optional<IdempotencyRecord> recordOpt = idempotencyRepository.findById(idemKey);
        if(recordOpt.isEmpty()){
            return new OrderStatusResponse(idemKey, "PENDING", null, null);
        }

        IdempotencyRecord record = recordOpt.get();
        Long orderId = record.getOrderId();

        if(orderId == null){
            return new OrderStatusResponse(
                    record.getIdemKey(),
                    record.getStatus().name(),
                    null,
                    null
            );
        }

        Orders order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        return new OrderStatusResponse(
                record.getIdemKey(),
                record.getStatus().name(),
                orderId,
                order.getStatus().name()
        );
    }
}
