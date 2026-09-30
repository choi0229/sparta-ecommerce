package org.teamsparta.orderapi.domain.order.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
public class OrderStatusCacheRepository {

    private static final String KEY_PREFIX = "order:status:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public OrderStatusCacheRepository(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        @Value("${order.status-cache.ttl:5m}") Duration ttl
    ){
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public Optional<OrderStatusResponse> find(String idemKey){
        String json = redisTemplate.opsForValue().get(key(idemKey));
        if(json == null){
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, OrderStatusResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("주문 상태 캐시 역직렬화 실패, miss로 처리. idemKey={}", idemKey, e);
            return Optional.empty();
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
        redisTemplate.opsForValue().set(key(idemKey), json, ttl);
    }

    public void evict(String idemKey) {
        redisTemplate.delete(key(idemKey));
    }

    private String key(String idemKey) {
        return KEY_PREFIX + idemKey;
    }
}
