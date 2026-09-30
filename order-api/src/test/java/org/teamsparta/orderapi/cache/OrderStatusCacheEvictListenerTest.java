package org.teamsparta.orderapi.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheEvictListener;
import org.teamsparta.orderapi.domain.order.cache.OrderStatusCacheRepository;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.event.OrderStatusChangedEvent;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderStatusCacheEvictListenerTest {

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Mock
    private OrderStatusCacheRepository cacheRepository;

    @InjectMocks
    private OrderStatusCacheEvictListener listener;

    private static final Long ORDER_ID = 1L;

    @Test
    @DisplayName("orderId에 연결된 멱등성 레코드마다 캐시를 삭제한다")
    void onOrderStatusChanged_evictsAllLinkedKeys() {
        given(idempotencyRepository.findAllByOrderId(ORDER_ID)).willReturn(List.of(
                completedRecord("idem-key-1"),
                completedRecord("idem-key-2")
        ));

        listener.onOrderStatusChanged(new OrderStatusChangedEvent(ORDER_ID));

        verify(cacheRepository).evict("idem-key-1");
        verify(cacheRepository).evict("idem-key-2");
    }

    @Test
    @DisplayName("연결된 멱등성 레코드가 없으면 캐시를 삭제하지 않는다")
    void onOrderStatusChanged_noLinkedRecord() {
        given(idempotencyRepository.findAllByOrderId(ORDER_ID)).willReturn(List.of());

        listener.onOrderStatusChanged(new OrderStatusChangedEvent(ORDER_ID));

        verify(cacheRepository, never()).evict(anyString());
    }

    @Test
    @DisplayName("orderId가 null이면 조회도 삭제도 하지 않는다")
    void onOrderStatusChanged_nullOrderId() {
        listener.onOrderStatusChanged(new OrderStatusChangedEvent(null));

        verifyNoInteractions(idempotencyRepository, cacheRepository);
    }

    private IdempotencyRecord completedRecord(String idemKey) {
        IdempotencyRecord record = IdempotencyRecord.start(idemKey, "hash");
        record.complete(ORDER_ID);
        return record;
    }
}