package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.util.Optional;

@Service
@RequiredArgsConstructor

public class OrderStatusQueryService {

    private final OrderStatusReader orderStatusReader;

    public OrderStatusResponse getOrderStatus(String idemKey) {
        return orderStatusReader.read(idemKey)
                .orElseGet(() -> new OrderStatusResponse(idemKey, "PENDING", null, null));
    }
}
