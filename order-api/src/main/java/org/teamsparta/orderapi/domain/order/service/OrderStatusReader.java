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
public class OrderStatusReader {

    private final IdempotencyRepository idempotencyRepository;
    private final OrderRepository orderRepository;

    /**
     * DB 주문 상태를 조회한다.
     * 멱등성 레코드가 없으면 빈 Optional을 반환한다.
     */
    @Transactional(readOnly = true)
    public Optional<OrderStatusResponse> read(String idemKey){
        Optional<IdempotencyRecord> recordOpt = idempotencyRepository.findById(idemKey);
        if(recordOpt.isEmpty()){
            return Optional.empty();
        }

        IdempotencyRecord record = recordOpt.get();
        Long orderId = record.getOrderId();

        if(orderId == null){
            return Optional.of(new OrderStatusResponse(record.getIdemKey(), record.getStatus().name(), null, null));
        }

        Orders order = orderRepository.findById(orderId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        return Optional.of(new OrderStatusResponse(record.getIdemKey(), record.getStatus().name(), orderId, order.getStatus().name()));
    }
}
