package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsparta.orderapi.domain.order.cache.CacheResult;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.util.Optional;

@Service
@RequiredArgsConstructor

public class OrderStatusQueryService {

    private final OrderStatusReader orderStatusReader;
    private final OrderStatusCacheRepository cacheRepository;

    public OrderStatusResponse getOrderStatus(String idemKey) {
        CacheResult<OrderStatusResponse> cached = cacheRepository.find(idemKey);
        if (cached.isHit()) {
            return cached.value();
        }

        Optional<OrderStatusResponse> loaded = orderStatusReader.read(idemKey);
        if (loaded.isEmpty()) {
            // 레코드가 아직 없는 PENDING은 캐시하지 않는다 (곧 생성될 레코드를 가리지 않도록)
            return new OrderStatusResponse(idemKey, "PENDING", null, null);
        }

        if (cached.isMiss()) {
            // Redis 장애(ERROR) 중에는 저장을 건너뛴다. 같은 요청에서 timeout을 두 번 겪지 않도록.
            cacheRepository.save(idemKey, loaded.get());
        }
        return loaded.get();
    }
}
