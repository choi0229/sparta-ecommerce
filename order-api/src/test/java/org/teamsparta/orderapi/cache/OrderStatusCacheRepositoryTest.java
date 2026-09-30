package org.teamsparta.orderapi.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderStatusCacheRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OrderStatusCacheRepository cacheRepository;

    private static final String IDEM_KEY = "seed-000001";
    private static final String REDIS_KEY = "order:status:seed-000001";
    private static final Duration TTL = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        cacheRepository = new OrderStatusCacheRepository(redisTemplate, objectMapper, TTL);
    }

    @Test
    @DisplayName("캐시에 키가 없으면 빈 Optional을 반환한다")
    void find_miss() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY)).willReturn(null);

        Optional<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("저장된 JSON을 OrderStatusResponse로 복원한다")
    void find_hit() {
        String json = """
                {"idemKey":"seed-000001","status":"COMPLETED","orderId":1,"orderStatus":"PAID"}
                """;
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY)).willReturn(json);

        Optional<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result).contains(
                new OrderStatusResponse("seed-000001", "COMPLETED", 1L, "PAID")
        );
    }

    @Test
    @DisplayName("깨진 JSON이면 예외 없이 빈 Optional을 반환한다")
    void find_corruptedJson() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY)).willReturn("{not-json");

        Optional<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("order:status:{idemKey} 키에 TTL과 함께 JSON으로 저장한다")
    void save() throws Exception {
        OrderStatusResponse response =
                new OrderStatusResponse("seed-000001", "COMPLETED", 1L, "PAID");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        cacheRepository.save(IDEM_KEY, response);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(REDIS_KEY), jsonCaptor.capture(), eq(TTL));
        assertThat(objectMapper.readValue(jsonCaptor.getValue(), OrderStatusResponse.class))
                .isEqualTo(response);
    }

    @Test
    @DisplayName("같은 키로 캐시를 삭제한다")
    void evict() {
        cacheRepository.evict(IDEM_KEY);

        verify(redisTemplate).delete(REDIS_KEY);
    }
}