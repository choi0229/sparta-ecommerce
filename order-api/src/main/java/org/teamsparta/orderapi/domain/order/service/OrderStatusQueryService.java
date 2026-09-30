package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.util.Optional;

@Service
@RequiredArgsConstructor

public class OrderStatusQueryService {

    private final OrderStatusReader orderStatusReader;
    private final OrderStatusCacheRepository cacheRepository;

    public OrderStatusResponse getOrderStatus(String idemKey) {
        Optional<OrderStatusResponse> cached = cacheRepository.find(idemKey);
        if(cached.isPresent()){
            return cached.get();
        }

        Optional<OrderStatusResponse> loaded = orderStatusReader.read(idemKey);
        if(loaded.isEmpty()){
            return new OrderStatusResponse(idemKey, "PENDING", null, null);
        }

        cacheRepository.save(idemKey, loaded.get());
        return loaded.get();
    }
}
