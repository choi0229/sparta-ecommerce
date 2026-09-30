package org.teamsparta.orderapi.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmedResult;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.service.IdempotencyService;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.order.service.OrderStatusQueryService;
import org.teamsparta.orderapi.global.enums.Status;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
class OrderStatusCacheEvictionIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @Autowired private OrderStatusQueryService orderStatusQueryService;
    @Autowired private OrderSagaService orderSagaService;
    @Autowired private IdempotencyService idempotencyService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderSagaStateRepository orderSagaStateRepository;
    @Autowired private IdempotencyRepository idempotencyRepository;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("Saga 상태 변경이 커밋되면 캐시가 삭제되고 다음 조회는 새 상태를 반환한다")
    void sagaStatusChange_evictsCacheAfterCommit() {
        // given: PAID 주문과 연결된 멱등성 레코드
        String idemKey = newKey();
        Orders order = Orders.createNew(newOrderNo(), 1L);
        order.updateStatus(Status.PAID);
        order = orderRepository.save(order);
        orderSagaStateRepository.save(OrderSagaState.start(order.getSagaId(), order.getId()));

        IdempotencyRecord record = IdempotencyRecord.start(idemKey, "hash");
        record.complete(order.getId());
        idempotencyRepository.save(record);

        // 첫 조회로 PAID가 캐시된다
        assertThat(orderStatusQueryService.getOrderStatus(idemKey).orderStatus()).isEqualTo("PAID");
        assertThat(cacheExists(idemKey)).isTrue();

        // when: 재고 확정으로 COMPLETED 전이 (트랜잭션 커밋)
        orderSagaService.onInventoryConfirmed(new InventoryConfirmedResult(
                UUID.randomUUID(), "inventory.confirmed", order.getId(), order.getSagaId()));

        // then: 캐시가 삭제되고, 다음 조회는 새 상태를 반환한다
        assertThat(cacheExists(idemKey)).isFalse();
        assertThat(orderStatusQueryService.getOrderStatus(idemKey).orderStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("멱등성 완료가 커밋되면 PENDING 캐시가 삭제되고 다음 조회는 COMPLETED를 반환한다")
    void idempotencyComplete_evictsPendingCacheAfterCommit() {
        // given: PENDING 레코드가 커밋되고 폴링으로 캐시된 상태
        String idemKey = newKey();
        idempotencyService.startOrThrow(idemKey, "hash");
        assertThat(orderStatusQueryService.getOrderStatus(idemKey).status()).isEqualTo("PENDING");
        assertThat(cacheExists(idemKey)).isTrue();

        // when: 주문 생성과 멱등성 완료를 한 트랜잭션에서 커밋
        transactionTemplate.executeWithoutResult(tx -> {
            Orders order = orderRepository.save(Orders.createNew(newOrderNo(), 1L));
            idempotencyService.complete(idemKey, order.getId());
        });

        // then
        assertThat(cacheExists(idemKey)).isFalse();
        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(idemKey);
        assertThat(response.status()).isEqualTo("COMPLETED");
        assertThat(response.orderStatus()).isEqualTo("CREATED");
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 캐시를 삭제하지 않는다")
    void rollback_keepsCache() {
        // given
        String idemKey = newKey();
        idempotencyService.startOrThrow(idemKey, "hash");
        orderStatusQueryService.getOrderStatus(idemKey);
        assertThat(cacheExists(idemKey)).isTrue();

        // when: 같은 작업을 하되 롤백
        transactionTemplate.executeWithoutResult(tx -> {
            Orders order = orderRepository.save(Orders.createNew(newOrderNo(), 1L));
            idempotencyService.complete(idemKey, order.getId());
            tx.setRollbackOnly();
        });

        // then: DB는 그대로 PENDING이고, 캐시도 그대로 남아 있다
        assertThat(cacheExists(idemKey)).isTrue();
        assertThat(orderStatusQueryService.getOrderStatus(idemKey).status()).isEqualTo("PENDING");
    }

    private boolean cacheExists(String idemKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("order:status:" + idemKey));
    }

    private String newKey() {
        return "it-" + UUID.randomUUID();
    }

    private String newOrderNo() {
        return "IT-" + UUID.randomUUID().toString().substring(0, 8);
    }
}