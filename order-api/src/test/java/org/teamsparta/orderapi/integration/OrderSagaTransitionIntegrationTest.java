package org.teamsparta.orderapi.integration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmFailedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservationExpiredResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReserveFailedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.payment.event.PaymentFailedEvent;
import org.teamsparta.orderapi.domain.payment.event.PaymentRequestedEvent;
import org.teamsparta.orderapi.domain.payment.event.PaymentSucceededEvent;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.enums.Status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saga 상태 전이표를 실제 PostgreSQL에서 검증한다. 출발 상태는 orders.status로 판단하고,
 * 토픽별 리스너가 동시에 처리해도 주문 행 잠금으로 직렬화되어 상태가 역행하지 않아야 한다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OrderSagaTransitionIntegrationTest {

    private static final String PAYMENT_REQUEST = "payment-request-event";
    private static final String CONFIRM_REQUEST = "order-confirm-event";

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
    private OrderSagaService orderSagaService;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderSagaStateRepository orderSagaStateRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM orders");
    }

    // ── 정상 전이 ─────────────────────────────────────────────────

    @Test
    @DisplayName("CREATED → RESERVED: 결제 요청 Outbox 저장, saga PAYMENT_REQUESTED, 예약 ID 저장")
    void reserved_fromCreated() {
        Fixture fixture = fixture(Status.CREATED, SagaState.INVENTORY_RESERVE_REQUESTED, null);
        UUID reservationId = UUID.randomUUID();

        orderSagaService.onInventoryReserved(fixture.reserved(reservationId));

        fixture.assertState(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);
        assertThat(orderSagaStateRepository.findById(fixture.order.getSagaId()).orElseThrow().getReservationId())
                .isEqualTo(reservationId);
        assertThat(outboxCount(PAYMENT_REQUEST)).isEqualTo(1);
    }

    @Test
    @DisplayName("CREATED → FAILED: 예약 실패 이벤트의 message가 failure_reason에 저장된다")
    void reserveFailed_fromCreated_savesMessageAsReason() {
        Fixture fixture = fixture(Status.CREATED, SagaState.INVENTORY_RESERVE_REQUESTED, null);

        orderSagaService.onInventoryReserveFailed(fixture.reserveFailed("재고가 부족합니다."));

        fixture.assertState(Status.FAILED, SagaState.FAILED, "재고가 부족합니다.");
    }

    @Test
    @DisplayName("RESERVED → PAID: 확정 요청 Outbox 저장, saga PAYMENT_COMPLETED")
    void paymentSucceeded_fromReserved() {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded());

        fixture.assertState(Status.PAID, SagaState.PAYMENT_COMPLETED, null);
        assertThat(outboxCount(CONFIRM_REQUEST)).isEqualTo(1);
    }

    @Test
    @DisplayName("RESERVED → FAILED: 결제 실패 사유가 failure_reason에 저장된다")
    void paymentFailed_fromReserved_savesReason() {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        orderSagaService.onPaymentFailed(fixture.paymentFailed("DECLINED"));

        fixture.assertState(Status.FAILED, SagaState.FAILED, "DECLINED");
    }

    @Test
    @DisplayName("PAID → COMPLETED")
    void confirmed_fromPaid() {
        Fixture fixture = fixture(Status.PAID, SagaState.PAYMENT_COMPLETED, null);

        orderSagaService.onInventoryConfirmed(fixture.confirmed());

        fixture.assertState(Status.COMPLETED, SagaState.COMPLETED, null);
    }

    @Test
    @DisplayName("RESERVED → EXPIRED: 만료 사유가 failure_reason에 저장된다")
    void expired_fromReserved_savesReason() {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        orderSagaService.onInventoryExpired(fixture.expired());

        fixture.assertState(Status.EXPIRED, SagaState.EXPIRED, "TTL_EXPIRED");
    }

    // ── 중복 이벤트 ───────────────────────────────────────────────

    @Test
    @DisplayName("재고 예약 이벤트 중복 → 결제 요청 Outbox 1건")
    void reserved_duplicate_oneOutbox() {
        Fixture fixture = fixture(Status.CREATED, SagaState.INVENTORY_RESERVE_REQUESTED, null);
        UUID reservationId = UUID.randomUUID();

        orderSagaService.onInventoryReserved(fixture.reserved(reservationId));
        orderSagaService.onInventoryReserved(fixture.reserved(reservationId));

        assertThat(outboxCount(PAYMENT_REQUEST)).isEqualTo(1);
        fixture.assertState(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);
    }

    @Test
    @DisplayName("결제 성공 이벤트 중복 → 확정 요청 Outbox 1건")
    void paymentSucceeded_duplicate_oneOutbox() {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded());
        orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded());

        assertThat(outboxCount(CONFIRM_REQUEST)).isEqualTo(1);
        fixture.assertState(Status.PAID, SagaState.PAYMENT_COMPLETED, null);
    }

    @Test
    @DisplayName("지연된 재고 예약·결제 성공 중복은 이후 단계로 진행한 주문을 되돌리지 않고 Outbox도 만들지 않는다")
    void lateDuplicates_doNotMoveProgressedOrderBackward() {
        Fixture paid = fixture(Status.PAID, SagaState.PAYMENT_COMPLETED, null);
        Fixture completed = fixture(Status.COMPLETED, SagaState.COMPLETED, null);

        orderSagaService.onInventoryReserved(paid.reserved(paid.reservationId()));
        orderSagaService.onInventoryReserved(completed.reserved(completed.reservationId()));
        orderSagaService.onPaymentSucceeded(completed.paymentSucceeded());

        paid.assertState(Status.PAID, SagaState.PAYMENT_COMPLETED, null);
        completed.assertState(Status.COMPLETED, SagaState.COMPLETED, null);
        assertThat(outboxCount(PAYMENT_REQUEST)).isZero();
        assertThat(outboxCount(CONFIRM_REQUEST)).isZero();
    }

    // ── 종료 상태 보호, 순서 오류 ─────────────────────────────────

    @ParameterizedTest(name = "{0} 주문에 {3}")
    @CsvSource({
            "COMPLETED, COMPLETED, , reserved",
            "COMPLETED, COMPLETED, , reserveFailed",
            "COMPLETED, COMPLETED, , paymentSucceeded",
            "COMPLETED, COMPLETED, , paymentFailed",
            "COMPLETED, COMPLETED, , confirmed",
            "COMPLETED, COMPLETED, , confirmFailed",
            "COMPLETED, COMPLETED, , expired",
            "FAILED, FAILED, ORIGINAL, reserved",
            "FAILED, FAILED, ORIGINAL, reserveFailed",
            "FAILED, FAILED, ORIGINAL, paymentSucceeded",
            "FAILED, FAILED, ORIGINAL, paymentFailed",
            "FAILED, FAILED, ORIGINAL, confirmed",
            "FAILED, FAILED, ORIGINAL, confirmFailed",
            "FAILED, FAILED, ORIGINAL, expired",
            "EXPIRED, EXPIRED, TTL_EXPIRED, reserved",
            "EXPIRED, EXPIRED, TTL_EXPIRED, reserveFailed",
            "EXPIRED, EXPIRED, TTL_EXPIRED, paymentSucceeded",
            "EXPIRED, EXPIRED, TTL_EXPIRED, paymentFailed",
            "EXPIRED, EXPIRED, TTL_EXPIRED, confirmed",
            "EXPIRED, EXPIRED, TTL_EXPIRED, confirmFailed",
            "EXPIRED, EXPIRED, TTL_EXPIRED, expired"
    })
    @DisplayName("종료 상태는 어떤 지연 이벤트로도 바뀌지 않고, 사유와 Outbox도 그대로다")
    void terminalStatus_isNeverOverwritten(Status status, SagaState sagaState, String reason, String event) {
        Fixture fixture = fixture(status, sagaState, reason);

        fixture.handle(event);

        fixture.assertState(status, sagaState, reason);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Long.class)).isZero();
    }

    @ParameterizedTest(name = "{0} 주문에 {2}")
    @CsvSource({
            "CREATED, INVENTORY_RESERVE_REQUESTED, paymentSucceeded",
            "CREATED, INVENTORY_RESERVE_REQUESTED, paymentFailed",
            "CREATED, INVENTORY_RESERVE_REQUESTED, confirmed",
            "CREATED, INVENTORY_RESERVE_REQUESTED, confirmFailed",
            "CREATED, INVENTORY_RESERVE_REQUESTED, expired",
            "RESERVED, PAYMENT_REQUESTED, reserveFailed",
            "RESERVED, PAYMENT_REQUESTED, confirmed",
            "RESERVED, PAYMENT_REQUESTED, confirmFailed",
            "PAID, PAYMENT_COMPLETED, reserveFailed",
            "PAID, PAYMENT_COMPLETED, paymentFailed",
            "PAID, PAYMENT_COMPLETED, expired"
    })
    @DisplayName("허용되지 않은 순서의 이벤트는 상태와 사유를 바꾸지 않는다")
    void outOfOrderEvent_changesNothing(Status status, SagaState sagaState, String event) {
        Fixture fixture = fixture(status, sagaState, null);

        fixture.handle(event);

        fixture.assertState(status, sagaState, null);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_event", Long.class)).isZero();
    }

    // ── 토픽 간 경합 ──────────────────────────────────────────────

    @Test
    @DisplayName("같은 주문에 결제 성공이 먼저 잠금을 얻으면 → PAID, 뒤따른 만료는 PAID를 유지한다")
    void race_paymentSucceededFirst_thenExpired() throws Exception {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        runBothWhileOrderIsLocked(fixture,
                () -> orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded()),
                () -> orderSagaService.onInventoryExpired(fixture.expired()));

        fixture.assertState(Status.PAID, SagaState.PAYMENT_COMPLETED, null);
        assertThat(outboxCount(CONFIRM_REQUEST)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문에 만료가 먼저 잠금을 얻으면 → EXPIRED, 뒤따른 결제 성공은 역행시키지 않고 확정을 요청하지 않는다")
    void race_expiredFirst_thenPaymentSucceeded() throws Exception {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        runBothWhileOrderIsLocked(fixture,
                () -> orderSagaService.onInventoryExpired(fixture.expired()),
                () -> orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded()));

        fixture.assertState(Status.EXPIRED, SagaState.EXPIRED, "TTL_EXPIRED");
        assertThat(outboxCount(CONFIRM_REQUEST)).isZero();
    }

    @Test
    @DisplayName("같은 주문에 결제 실패가 먼저 잠금을 얻으면 → FAILED, 뒤따른 결제 성공은 무시된다")
    void race_paymentFailedFirst_thenPaymentSucceeded() throws Exception {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        runBothWhileOrderIsLocked(fixture,
                () -> orderSagaService.onPaymentFailed(fixture.paymentFailed("DECLINED")),
                () -> orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded()));

        fixture.assertState(Status.FAILED, SagaState.FAILED, "DECLINED");
        assertThat(outboxCount(CONFIRM_REQUEST)).isZero();
    }

    @RepeatedTest(20)
    @DisplayName("결제 성공과 만료를 잠금 없이 동시에 실행해도 결과가 정확히 하나이고 Outbox가 그에 맞다")
    void freeRace_paymentSucceededAndExpired() throws Exception {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        runConcurrently(
                () -> orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded()),
                () -> orderSagaService.onInventoryExpired(fixture.expired()));

        Status status = orderRepository.findById(fixture.order.getId()).orElseThrow().getStatus();
        assertThat(status).isIn(Status.PAID, Status.EXPIRED);
        if (status == Status.PAID) {
            fixture.assertState(Status.PAID, SagaState.PAYMENT_COMPLETED, null);
            assertThat(outboxCount(CONFIRM_REQUEST)).isEqualTo(1);
        } else {
            fixture.assertState(Status.EXPIRED, SagaState.EXPIRED, "TTL_EXPIRED");
            assertThat(outboxCount(CONFIRM_REQUEST)).isZero();
        }
        cleanUp();
    }

    @RepeatedTest(20)
    @DisplayName("같은 재고 예약 이벤트를 동시에 처리해도 결제 요청 Outbox는 1건이다")
    void freeRace_duplicateReserved_oneOutbox() throws Exception {
        Fixture fixture = fixture(Status.CREATED, SagaState.INVENTORY_RESERVE_REQUESTED, null);
        UUID reservationId = UUID.randomUUID();

        runConcurrently(
                () -> orderSagaService.onInventoryReserved(fixture.reserved(reservationId)),
                () -> orderSagaService.onInventoryReserved(fixture.reserved(reservationId)));

        assertThat(outboxCount(PAYMENT_REQUEST)).isEqualTo(1);
        cleanUp();
    }

    @RepeatedTest(20)
    @DisplayName("같은 결제 성공 이벤트를 동시에 처리해도 확정 요청 Outbox는 1건이다")
    void freeRace_duplicatePaymentSucceeded_oneOutbox() throws Exception {
        Fixture fixture = fixture(Status.RESERVED, SagaState.PAYMENT_REQUESTED, null);

        runConcurrently(
                () -> orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded()),
                () -> orderSagaService.onPaymentSucceeded(fixture.paymentSucceeded()));

        assertThat(outboxCount(CONFIRM_REQUEST)).isEqualTo(1);
        cleanUp();
    }

    private void runBothWhileOrderIsLocked(Fixture fixture, Runnable first, Runnable second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, fixture.order.getId());
                lock.executeQuery();
            }

            Future<?> firstResult = executor.submit(first);
            awaitLockWaiters(1);
            Future<?> secondResult = executor.submit(second);
            awaitLockWaiters(2);

            holder.commit();
            firstResult.get(30, TimeUnit.SECONDS);
            secondResult.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void runConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> awaitThenRun(start, first));
            Future<?> secondResult = executor.submit(() -> awaitThenRun(start, second));
            start.countDown();
            firstResult.get(30, TimeUnit.SECONDS);
            secondResult.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private void awaitThenRun(CountDownLatch start, Runnable task) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        task.run();
    }

    private void awaitLockWaiters(int expected) {
        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(20, TimeUnit.MILLISECONDS).until(() ->
                jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """, Long.class) >= expected);
    }

    private long outboxCount(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Long.class, eventType);
    }

    private Fixture fixture(Status status, SagaState sagaState, String reason) {
        Orders order = Orders.createNew("O-" + UUID.randomUUID().toString().substring(0, 8), 1L);
        order.updateStatus(status);
        Orders saved = orderRepository.save(order);
        if (reason != null) {
            jdbcTemplate.update("UPDATE orders SET failure_reason = ? WHERE id = ?", reason, saved.getId());
        }
        UUID reservationId = sagaState == SagaState.INVENTORY_RESERVE_REQUESTED ? null : UUID.randomUUID();
        OrderSagaState saga = OrderSagaState.start(saved.getSagaId(), saved.getId());
        saga.updateState(sagaState, reason, reservationId);
        orderSagaStateRepository.save(saga);
        return new Fixture(saved);
    }

    private class Fixture {
        final Orders order;

        Fixture(Orders order) {
            this.order = order;
        }

        UUID reservationId() {
            return orderSagaStateRepository.findById(order.getSagaId()).orElseThrow().getReservationId();
        }

        InventoryReservedResult reserved(UUID reservationId) {
            return new InventoryReservedResult(UUID.randomUUID(), "inventory.reserved", order.getSagaId(),
                    order.getId(), reservationId, null, null);
        }

        InventoryReserveFailedResult reserveFailed(String message) {
            return new InventoryReserveFailedResult(UUID.randomUUID(), "inventory.reserved.failed",
                    order.getSagaId(), order.getId(), message);
        }

        PaymentSucceededEvent paymentSucceeded() {
            return PaymentSucceededEvent.from(paymentRequest());
        }

        PaymentFailedEvent paymentFailed(String reason) {
            return PaymentFailedEvent.from(paymentRequest(), reason);
        }

        private PaymentRequestedEvent paymentRequest() {
            return PaymentRequestedEvent.from(order.getId(), order.getSagaId(), 1L, BigDecimal.TEN);
        }

        InventoryConfirmedResult confirmed() {
            return new InventoryConfirmedResult(UUID.randomUUID(), "inventory.confirmed", order.getId(), order.getSagaId());
        }

        InventoryReservationExpiredResult expired() {
            return new InventoryReservationExpiredResult(UUID.randomUUID(), "inventory.expired",
                    order.getId(), order.getSagaId(), reservationId());
        }

        void handle(String event) {
            Consumer<OrderSagaService> handler = switch (event) {
                case "reserved" -> service -> service.onInventoryReserved(reserved(UUID.randomUUID()));
                case "reserveFailed" -> service -> service.onInventoryReserveFailed(reserveFailed("LATE"));
                case "paymentSucceeded" -> service -> service.onPaymentSucceeded(paymentSucceeded());
                case "paymentFailed" -> service -> service.onPaymentFailed(paymentFailed("LATE"));
                case "confirmed" -> service -> service.onInventoryConfirmed(confirmed());
                case "confirmFailed" -> service -> service.onInventoryConfirmFailed(new InventoryConfirmFailedResult(
                        UUID.randomUUID(), "inventory.confirm.failed", order.getId(), order.getSagaId(),
                        reservationId(), "EXPIRED"));
                case "expired" -> service -> service.onInventoryExpired(expired());
                default -> throw new IllegalArgumentException(event);
            };
            handler.accept(orderSagaService);
        }

        void assertState(Status status, SagaState sagaState, String reason) {
            Orders current = orderRepository.findById(order.getId()).orElseThrow();
            OrderSagaState saga = orderSagaStateRepository.findById(order.getSagaId()).orElseThrow();
            assertThat(current.getStatus()).isEqualTo(status);
            assertThat(current.getFailureReason()).isEqualTo(reason);
            assertThat(saga.getState()).isEqualTo(sagaState);
            assertThat(saga.getLastError()).isEqualTo(reason);
        }
    }
}
