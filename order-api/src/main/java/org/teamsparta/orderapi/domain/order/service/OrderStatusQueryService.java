package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;

import java.util.Optional;

@Service
@RequiredArgsConstructor

public class OrderStatusQueryService {

    private final IdempotencyRepository idempotencyRepository;

    public OrderStatusResponse getOrderStatus(String idemKey) {
        Optional<IdempotencyRecord> recordOpt = idempotencyRepository.findById(idemKey);
        if(recordOpt.isEmpty()){
            return new OrderStatusResponse(idemKey, "PENDING", null);
        }
        IdempotencyRecord record = recordOpt.get();
        return new OrderStatusResponse(
                record.getIdemKey(),
                record.getStatus().name(),
                record.getOrderId()
        );
    }
}
