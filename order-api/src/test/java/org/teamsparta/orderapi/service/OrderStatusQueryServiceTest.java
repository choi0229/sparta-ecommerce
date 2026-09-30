package org.teamsparta.orderapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.orderapi.domain.order.cache.CacheResult;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.service.OrderStatusQueryService;
import org.teamsparta.orderapi.domain.order.service.OrderStatusReader;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderStatusQueryServiceTest {

    @Mock
    private OrderStatusReader orderStatusReader;

    @Mock
    private OrderStatusCacheRepository cacheRepository;

    @InjectMocks
    private OrderStatusQueryService orderStatusQueryService;

    private static final String IDEM_KEY = "idem-key-1";
    private static final OrderStatusResponse LOADED =
            new OrderStatusResponse(IDEM_KEY, "COMPLETED", 1L, "PAID");

    @Test
    @DisplayName("캐시에 있으면 캐시 값을 반환하고 DB는 조회하지 않는다")
    void getOrderStatus_cacheHit() {
        given(cacheRepository.find(IDEM_KEY)).willReturn(CacheResult.hit(LOADED));

        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(IDEM_KEY);

        assertThat(response).isEqualTo(LOADED);
        verify(orderStatusReader, never()).read(anyString());
        verify(cacheRepository, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("캐시에 없고 DB에 있으면 DB 결과를 반환하고 캐시에 저장한다")
    void getOrderStatus_cacheMiss_found() {
        given(cacheRepository.find(IDEM_KEY)).willReturn(CacheResult.miss());
        given(orderStatusReader.read(IDEM_KEY)).willReturn(Optional.of(LOADED));

        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(IDEM_KEY);

        assertThat(response).isEqualTo(LOADED);
        verify(cacheRepository).save(IDEM_KEY, LOADED);
    }

    @Test
    @DisplayName("캐시에도 DB에도 없으면 PENDING을 반환하고 캐시에 저장하지 않는다")
    void getOrderStatus_cacheMiss_notFound() {
        given(cacheRepository.find(IDEM_KEY)).willReturn(CacheResult.miss());
        given(orderStatusReader.read(IDEM_KEY)).willReturn(Optional.empty());

        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(IDEM_KEY);

        assertThat(response).isEqualTo(new OrderStatusResponse(IDEM_KEY, "PENDING", null, null));
        verify(cacheRepository, never()).save(anyString(), any());
    }

    @Test
    @DisplayName("Redis 장애면 DB 결과를 반환하고 캐시 저장은 건너뛴다")
    void getOrderStatus_cacheError_fallbackToDb() {
        given(cacheRepository.find(IDEM_KEY)).willReturn(CacheResult.error());
        given(orderStatusReader.read(IDEM_KEY)).willReturn(Optional.of(LOADED));

        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(IDEM_KEY);

        assertThat(response).isEqualTo(LOADED);
        verify(cacheRepository, never()).save(anyString(), any());
    }
}