package org.teamsparta.orderapi.domain.order.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.event.OrderStatusChangedEvent;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;

@Component
@RequiredArgsConstructor
public class OrderStatusCacheEvictListener {

    private final IdempotencyRepository idempotencyRepository;
    private final OrderStatusCacheRepository cacheRepository;

    /**
     * 주문 상태가 바뀐 트랜잭션이 커밋된 뒤 해당 주문의 상태 캐시를 삭제한다.
     * 주의: @TransactionalEventListener는 트랜잭션 밖에서 발행된 이벤트를 처리하지 않는다.
     * OrderStatusChangedEvent는 반드시 트랜잭션 안에서 발행해야 한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.orderId() == null) {
            return;
        }
        idempotencyRepository.findAllByOrderId(event.orderId())
                .stream()
                .map(IdempotencyRecord::getIdemKey)
                .forEach(cacheRepository::evict);
    }
}