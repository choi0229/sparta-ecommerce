package org.teamsparta.orderapi.domain.order.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.global.enums.Status;

import java.time.Duration;
import java.util.Set;

@Slf4j
@Component
public class OrderStatusCacheRepository {

    private static final String KEY_PREFIX = "order:status:";

    private static final Set<String> FINAL_ORDER_STATUSES = Set.of(
            Status.COMPLETED.name(), Status.FAILED.name(),
            Status.CANCELED.name(), Status.EXPIRED.name()
    );

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration inProgressTtl;
    private final Duration finalTtl;

    public OrderStatusCacheRepository(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${order.status-cache.ttl.in-progress:5s}") Duration inProgressTtl,
        @Value("${order.status-cache.ttl.final:5m}") Duration finalTtl
    ){
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.inProgressTtl = inProgressTtl;
        this.finalTtl = finalTtl;
    }

    public CacheResult<OrderStatusResponse> find(String idemKey) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(key(idemKey));
        } catch (DataAccessException e) {
            log.warn("주문 상태 캐시 조회 실패, DB로 fallback. idemKey={}, cause={}", idemKey, e.getMessage());
            return CacheResult.error();
        }

        if (json == null) {
            return CacheResult.miss();
        }
        try {
            return CacheResult.hit(objectMapper.readValue(json, OrderStatusResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("주문 상태 캐시 역직렬화 실패, miss로 처리. idemKey={}", idemKey, e);
            return CacheResult.miss();
        }
    }

    public void save(String idemKey, OrderStatusResponse response) {
        String json;
        try {
            json = objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.warn("주문 상태 캐시 직렬화 실패, 저장 생략. idemKey={}", idemKey, e);
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(idemKey), json, ttlFor(response));
        } catch (DataAccessException e) {
            log.warn("주문 상태 캐시 저장 실패. idemKey={}, cause={}", idemKey, e.getMessage());
        }
    }

    public void evict(String idemKey) {
        try {
            redisTemplate.delete(key(idemKey));
        } catch (DataAccessException e) {
            log.warn("주문 상태 캐시 삭제 실패, TTL 만료에 맡김. idemKey={}, cause={}", idemKey, e.getMessage());
        }
    }

    private String key(String idemKey) {
        return KEY_PREFIX + idemKey;
    }

    /**
     * 더 이상 바뀌지 않는 상태는 길게, 바뀔 수 있는 상태는 짧게 캐시한다.
     * 조회-무효화 경쟁 조건으로 옛날 값이 캐시되더라도 진행 중 상태는 짧은 TTL로 빨리 해소된다.
     */
    private Duration ttlFor(OrderStatusResponse response) {
        if (response.orderStatus() != null) {
            return FINAL_ORDER_STATUSES.contains(response.orderStatus()) ? finalTtl : inProgressTtl;
        }
        // 주문 없이 끝난 멱등성 요청(FAILED)은 완료 상태, PENDING은 진행 중
        return "FAILED".equals(response.status()) ? finalTtl : inProgressTtl;
    }
}
