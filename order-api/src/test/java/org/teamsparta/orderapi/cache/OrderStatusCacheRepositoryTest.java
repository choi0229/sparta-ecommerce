package org.teamsparta.orderapi.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
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
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(5);
    private static final Duration FINAL_TTL = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        cacheRepository = new OrderStatusCacheRepository(redisTemplate, objectMapper, IN_PROGRESS_TTL, FINAL_TTL);
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
    @DisplayName("같은 키로 캐시를 삭제한다")
    void evict() {
        cacheRepository.evict(IDEM_KEY);

        verify(redisTemplate).delete(REDIS_KEY);
    }

    @Test
    @DisplayName("캐시 삭제 중 Redis 예외가 나도 호출자에게 전파하지 않는다")
    void evict_redisFailure() {
        given(redisTemplate.delete(REDIS_KEY))
                .willThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> cacheRepository.evict(IDEM_KEY))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "status={0}, orderStatus={1} → TTL {2}초")
    @CsvSource(nullValues = "null", value = {
            "COMPLETED, COMPLETED, 300",
            "COMPLETED, FAILED,    300",
            "COMPLETED, CANCELED,  300",
            "COMPLETED, EXPIRED,   300",
            "COMPLETED, PAID,      5",
            "COMPLETED, RESERVED,  5",
            "COMPLETED, CREATED,   5",
            "PENDING,   null,      5",
            "FAILED,    null,      300"
    })
    @DisplayName("주문 상태에 따라 TTL을 다르게 저장한다")
    void save_ttlByStatus(String status, String orderStatus, long expectedTtlSeconds) throws Exception {
        OrderStatusResponse response = new OrderStatusResponse(IDEM_KEY, status, 1L, orderStatus);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        cacheRepository.save(IDEM_KEY, response);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(REDIS_KEY), jsonCaptor.capture(), eq(Duration.ofSeconds(expectedTtlSeconds)));
        assertThat(objectMapper.readValue(jsonCaptor.getValue(), OrderStatusResponse.class))
                .isEqualTo(response);
    }
}