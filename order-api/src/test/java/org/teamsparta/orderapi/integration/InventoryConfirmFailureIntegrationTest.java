package org.teamsparta.orderapi.integration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmFailedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservationExpiredResult;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.enums.Status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * inventory-confirm-failed-event의 수신과 PAID → FAILED 전이를 실제 PostgreSQL과 EmbeddedKafka로 검증한다.
 * 수신 페이로드는 inventory-api의 InventoryConfirmFailedPublishIntegrationTest가 발행을 검증하는 계약과 같다.
 */
@Tag("integration")
@SpringBootTest(properties = "scheduler.outbox.enabled=false")
@EmbeddedKafka(
        partitions = 1,
        topics = {"inventory-confirm-failed-event"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InventoryConfirmFailureIntegrationTest {

    private static final String TOPIC = "inventory-confirm-failed-event";
    private static final String REASON = "RESERVATION_EXPIRED_AFTER_PAYMENT";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("orderdb")
            .withUsername("root")
            .withPassword("rootpassword");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private OrderSagaService orderSagaService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderSagaStateRepository orderSagaStateRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        orderRepository.deleteAll();
    }

    @Test
    @DisplayName("Kafka로 수신한 확정 실패 이벤트가 PAID 주문을 FAILED로 바꾸고 사유를 저장한다")
    void consumedEvent_movesPaidOrderToFailed() throws Exception {
        Orders order = saveOrder(Status.PAID, SagaState.PAYMENT_COMPLETED);
        UUID reservationId = reservationIdOf(order);
        String payload = """
                {"eventId":"%s","eventType":"inventory.confirm.failed","orderId":%d,"sagaId":"%s","reservationId":"%s","reservationStatus":"EXPIRED"}
                """.formatted(UUID.randomUUID(), order.getId(), order.getSagaId(), reservationId);

        kafkaTemplate.send(TOPIC, order.getId().toString(), payload).get(5, TimeUnit.SECONDS);

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reload(order).getStatus()).isEqualTo(Status.FAILED));
        assertThat(reload(order).getFailureReason()).isEqualTo(REASON);
        OrderSagaState saga = orderSagaStateRepository.findById(order.getSagaId()).orElseThrow();
        assertThat(saga.getState()).isEqualTo(SagaState.FAILED);
        assertThat(saga.getLastError()).isEqualTo(REASON);
        assertThat(saga.getReservationId()).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("같은 실패 이벤트를 다시 받아도 상태 변경은 한 번만 적용된다")
    void duplicateEvent_appliesOnlyOnce() {
        Orders order = saveOrder(Status.PAID, SagaState.PAYMENT_COMPLETED);
        InventoryConfirmFailedResult event = confirmFailed(order);

        orderSagaService.onInventoryConfirmFailed(event);
        ZonedDateTime updatedAt = updatedAtOf(order);

        orderSagaService.onInventoryConfirmFailed(event);

        assertThat(reload(order).getStatus()).isEqualTo(Status.FAILED);
        assertThat(reload(order).getFailureReason()).isEqualTo(REASON);
        assertThat(updatedAtOf(order)).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("COMPLETED 주문은 실패 이벤트로 덮어쓰지 않는다")
    void completedOrder_isNotOverwritten() {
        Orders order = saveOrder(Status.COMPLETED, SagaState.COMPLETED);

        orderSagaService.onInventoryConfirmFailed(confirmFailed(order));

        assertThat(reload(order).getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(reload(order).getFailureReason()).isNull();
        assertThat(orderSagaStateRepository.findById(order.getSagaId()).orElseThrow().getState())
                .isEqualTo(SagaState.COMPLETED);
    }

    @Test
    @DisplayName("실패 이벤트 처리는 주문 행 잠금을 기다린 뒤 최신 상태로 판단한다")
    void confirmFailed_waitsForOrderRowLockAndUsesLatestStatus() throws Exception {
        Orders order = saveOrder(Status.PAID, SagaState.PAYMENT_COMPLETED);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            holder.setAutoCommit(false);
            try (PreparedStatement update = holder.prepareStatement(
                    "UPDATE orders SET status = 'COMPLETED' WHERE id = ?")) {
                update.setLong(1, order.getId());
                update.executeUpdate();
            }

            Future<?> handling = executor.submit(() -> orderSagaService.onInventoryConfirmFailed(confirmFailed(order)));
            Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """, Long.class) == 1);

            holder.commit();
            handling.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(reload(order).getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(reload(order).getFailureReason()).isNull();
    }

    @Test
    @DisplayName("PAID 주문에 예약 만료 이벤트가 오면 PAID를 유지하고 확정 결과를 기다린다")
    void expiredEvent_keepsPaidOrder() {
        Orders order = saveOrder(Status.PAID, SagaState.PAYMENT_COMPLETED);

        orderSagaService.onInventoryExpired(expired(order));

        assertThat(reload(order).getStatus()).isEqualTo(Status.PAID);
        assertThat(orderSagaStateRepository.findById(order.getSagaId()).orElseThrow().getState())
                .isEqualTo(SagaState.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("만료 이벤트가 먼저 와도 뒤따르는 확정 실패 이벤트로 PAID → FAILED가 된다")
    void expiredThenConfirmFailed_endsFailed() {
        Orders order = saveOrder(Status.PAID, SagaState.PAYMENT_COMPLETED);

        orderSagaService.onInventoryExpired(expired(order));
        orderSagaService.onInventoryConfirmFailed(confirmFailed(order));

        assertThat(reload(order).getStatus()).isEqualTo(Status.FAILED);
        assertThat(reload(order).getFailureReason()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("RESERVED 주문의 예약 만료는 기존대로 EXPIRED가 된다")
    void expiredEvent_movesReservedOrderToExpired() {
        Orders order = saveOrder(Status.RESERVED, SagaState.PAYMENT_REQUESTED);

        orderSagaService.onInventoryExpired(expired(order));

        assertThat(reload(order).getStatus()).isEqualTo(Status.EXPIRED);
    }

    private Orders saveOrder(Status status, SagaState sagaState) {
        Orders order = Orders.createNew("O-" + UUID.randomUUID().toString().substring(0, 8), 1L);
        order.updateStatus(status);
        Orders saved = orderRepository.save(order);
        OrderSagaState saga = OrderSagaState.start(saved.getSagaId(), saved.getId());
        saga.updateState(sagaState, null, UUID.randomUUID());
        orderSagaStateRepository.save(saga);
        return saved;
    }

    private UUID reservationIdOf(Orders order) {
        return orderSagaStateRepository.findById(order.getSagaId()).orElseThrow().getReservationId();
    }

    private InventoryConfirmFailedResult confirmFailed(Orders order) {
        return new InventoryConfirmFailedResult(UUID.randomUUID(), "inventory.confirm.failed",
                order.getId(), order.getSagaId(), reservationIdOf(order), "EXPIRED");
    }

    private InventoryReservationExpiredResult expired(Orders order) {
        return new InventoryReservationExpiredResult(UUID.randomUUID(), "inventory.expired",
                order.getId(), order.getSagaId(), reservationIdOf(order));
    }

    private Orders reload(Orders order) {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private ZonedDateTime updatedAtOf(Orders order) {
        return reload(order).getUpdatedAt();
    }
}
