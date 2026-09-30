package org.teamsparta.orderapi.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.teamsparta.orderapi.domain.order.cache.CacheResult;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderStatusCacheRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private SimpleMeterRegistry meterRegistry;
    private OrderStatusCacheRepository cacheRepository;

    private static final String IDEM_KEY = "seed-000001";
    private static final String REDIS_KEY = "order:status:seed-000001";
    private static final Duration IN_PROGRESS_TTL = Duration.ofSeconds(5);
    private static final Duration FINAL_TTL = Duration.ofMinutes(5);

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        cacheRepository = new OrderStatusCacheRepository(
                redisTemplate, objectMapper, meterRegistry, IN_PROGRESS_TTL, FINAL_TTL);
    }

    @Test
    @DisplayName("캐시에 키가 없으면 MISS를 반환하고 miss를 센다")
    void find_miss() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY)).willReturn(null);

        CacheResult<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result.isMiss()).isTrue();
        assertThat(requestCount("miss")).isEqualTo(1);
    }

    @Test
    @DisplayName("저장된 JSON을 복원해 HIT를 반환하고 hit을 센다")
    void find_hit() {
        String json = """
                {"idemKey":"seed-000001","status":"COMPLETED","orderId":1,"orderStatus":"PAID"}
                """;
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY)).willReturn(json);

        CacheResult<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result.isHit()).isTrue();
        assertThat(result.value()).isEqualTo(
                new OrderStatusResponse("seed-000001", "COMPLETED", 1L, "PAID")
        );
        assertThat(requestCount("hit")).isEqualTo(1);
    }

    @Test
    @DisplayName("깨진 JSON이면 MISS를 반환하고 miss만 센다 (hit과 중복 집계하지 않음)")
    void find_corruptedJson() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY)).willReturn("{not-json");

        CacheResult<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result.isMiss()).isTrue();
        assertThat(requestCount("miss")).isEqualTo(1);
        assertThat(requestCount("hit")).isZero();
    }

    @Test
    @DisplayName("Redis 조회가 실패하면 ERROR를 반환하고 error를 센다")
    void find_redisFailure() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get(REDIS_KEY))
                .willThrow(new QueryTimeoutException("Redis command timed out"));

        CacheResult<OrderStatusResponse> result = cacheRepository.find(IDEM_KEY);

        assertThat(result.isError()).isTrue();
        assertThat(requestCount("error")).isEqualTo(1);
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

    @Test
    @DisplayName("캐시 저장 중 Redis 예외가 나도 전파하지 않고 save 실패를 센다")
    void save_redisFailure() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new QueryTimeoutException("Redis command timed out"))
                .given(valueOperations).set(eq(REDIS_KEY), anyString(), any(Duration.class));

        assertThatCode(() -> cacheRepository.save(IDEM_KEY,
                new OrderStatusResponse(IDEM_KEY, "COMPLETED", 1L, "COMPLETED")))
                .doesNotThrowAnyException();
        assertThat(writeErrorCount("save")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키로 캐시를 삭제한다")
    void evict() {
        cacheRepository.evict(IDEM_KEY);

        verify(redisTemplate).delete(REDIS_KEY);
    }

    @Test
    @DisplayName("캐시 삭제 중 Redis 예외가 나도 전파하지 않고 evict 실패를 센다")
    void evict_redisFailure() {
        given(redisTemplate.delete(REDIS_KEY))
                .willThrow(new RedisConnectionFailureException("redis down"));

        assertThatCode(() -> cacheRepository.evict(IDEM_KEY))
                .doesNotThrowAnyException();
        assertThat(writeErrorCount("evict")).isEqualTo(1);
    }

    private double requestCount(String result) {
        return meterRegistry.get("order.status.cache.requests")
                .tag("result", result).counter().count();
    }

    private double writeErrorCount(String operation) {
        return meterRegistry.get("order.status.cache.write.errors")
                .tag("operation", operation).counter().count();
    }
}