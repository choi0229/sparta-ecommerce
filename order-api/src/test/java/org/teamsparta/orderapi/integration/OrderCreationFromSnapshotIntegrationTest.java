package org.teamsparta.orderapi.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult.Item;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult.ProductSnapshotItem;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.order.service.OrderTransactionalService;
import org.teamsparta.orderapi.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상품 스냅샷 응답으로 주문을 만드는 단계를 실제 PostgreSQL에서 검증한다.
 * 비즈니스 실패는 FAILED로 커밋되고, 시스템 오류는 전체가 롤백되어 재시도할 수 있어야 한다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class OrderCreationFromSnapshotIntegrationTest {

    private static final ProductSnapshotItem SNAP_A = snapshot("SKU-A", "1000");
    private static final ProductSnapshotItem SNAP_B = snapshot("SKU-B", "500");

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
    private OrderTransactionalService orderTransactionalService;
    @Autowired
    private OrderSagaService orderSagaService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM idempotency_request");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    @DisplayName("정상 스냅샷 → 주문 생성, 총액 저장, categoryPath null, 멱등 기록 COMPLETED, Outbox 1건")
    void validSnapshot_createsOrder() {
        String key = pendingKey();

        orderTransactionalService.createOrderInternal(reply(key, List.of(SNAP_A, SNAP_B),
                List.of(new Item("SKU-A", 2), new Item("SKU-B", 1))), key);

        Map<String, Object> order = jdbcTemplate.queryForMap("SELECT * FROM orders");
        assertThat(order.get("status")).isEqualTo("CREATED");
        assertThat((BigDecimal) order.get("total_amount")).isEqualByComparingTo("2500");
        assertThat((BigDecimal) order.get("pay_amount")).isEqualByComparingTo("2500");
        assertThat(jdbcTemplate.queryForList("SELECT category_path_snapshot FROM order_item", String.class))
                .hasSize(2).containsOnlyNulls();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_id FROM idempotency_request WHERE idem_key = ?", Long.class, key)).isEqualTo(order.get("id"));
        assertThat(outboxCount("order-create-event")).isEqualTo(1);
    }

    @Test
    @DisplayName("주문 총액이 재고 예약 후 결제 요청 금액으로 전달된다")
    void paymentRequest_carriesOrderTotal() throws Exception {
        String key = pendingKey();
        orderTransactionalService.createOrderInternal(reply(key, List.of(SNAP_A, SNAP_B),
                List.of(new Item("SKU-A", 2), new Item("SKU-B", 1))), key);
        Map<String, Object> order = jdbcTemplate.queryForMap("SELECT id, saga_id FROM orders");

        orderSagaService.onInventoryReserved(new InventoryReservedResult(
                UUID.randomUUID(), "inventory.reserved", (UUID) order.get("saga_id"), (Long) order.get("id"),
                UUID.randomUUID(), ZonedDateTime.now().plusMinutes(30), List.of()));

        JsonNode payload = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'payment-request-event'", String.class));
        assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("2500");
    }

    @Test
    @DisplayName("같은 스냅샷 응답을 2회 처리해도 주문은 1건이다")
    void duplicateReply_createsOneOrder() {
        String key = pendingKey();
        ProductSnapshotReplyResult reply = reply(key, List.of(SNAP_A), List.of(new Item("SKU-A", 1)));

        orderTransactionalService.createOrderInternal(reply, key);
        orderTransactionalService.createOrderInternal(reply, key);

        assertThat(orderCount()).isEqualTo(1);
        assertThat(outboxCount("order-create-event")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 스냅샷 응답을 동시에 처리해도 주문은 1건이다")
    void concurrentDuplicateReply_createsOneOrder() throws Exception {
        String key = pendingKey();
        ProductSnapshotReplyResult reply = reply(key, List.of(SNAP_A), List.of(new Item("SKU-A", 1)));

        runConcurrently(List.of(
                () -> orderTransactionalService.createOrderInternal(reply, key),
                () -> orderTransactionalService.createOrderInternal(reply, key)));

        assertThat(orderCount()).isEqualTo(1);
        assertThat(outboxCount("order-create-event")).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 주문을 동시에 생성해도 주문번호가 충돌하지 않는다")
    void concurrentOrders_haveUniqueOrderNumbers() throws Exception {
        int orders = 32;
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            String key = pendingKey();
            ProductSnapshotReplyResult reply = reply(key, List.of(SNAP_A), List.of(new Item("SKU-A", 1)));
            tasks.add(() -> orderTransactionalService.createOrderInternal(reply, key));
        }

        runConcurrently(tasks);

        assertThat(jdbcTemplate.queryForObject("SELECT count(DISTINCT order_no) FROM orders", Long.class))
                .isEqualTo((long) orders);
        assertThat(jdbcTemplate.queryForList("SELECT order_no FROM orders", String.class))
                .allSatisfy(orderNo -> assertThat(orderNo.length()).isLessThanOrEqualTo(32));
    }

    @Test
    @DisplayName("스냅샷 실패 응답(items=null) → FAILED와 사유 저장, 주문 없음")
    void failedReply_marksFailedWithoutOrder() {
        String key = pendingKey();

        orderTransactionalService.failOrder(key, "MISSING_SKU=[SKU-X]");

        assertFailed(key, "MISSING_SKU=[SKU-X]");
    }

    @Test
    @DisplayName("success=true인데 items=null이어도 NPE 없이 FAILED로 끝난다")
    void nullSnapshotItems_marksFailed() {
        String key = pendingKey();

        orderTransactionalService.createOrderInternal(reply(key, null, List.of(new Item("SKU-A", 1))), key);

        assertFailed(key, "EMPTY_PRODUCT_SNAPSHOT");
    }

    @Test
    @DisplayName("스냅샷에 없는 SKU → FAILED와 사유 저장, 주문 없음")
    void missingSku_marksFailed() {
        String key = pendingKey();

        orderTransactionalService.createOrderInternal(reply(key, List.of(SNAP_A),
                List.of(new Item("SKU-A", 1), new Item("SKU-X", 1))), key);

        assertFailed(key, "MISSING_SKU=[SKU-X]");
    }

    @Test
    @DisplayName("잘못된 수량 이벤트 → FAILED와 사유 저장, 주문 없음 (고아 주문 방지)")
    void invalidQuantity_marksFailedWithoutOrphanOrder() {
        String zero = pendingKey();
        String nullQuantity = pendingKey();

        orderTransactionalService.createOrderInternal(reply(zero, List.of(SNAP_A, SNAP_B),
                List.of(new Item("SKU-A", 1), new Item("SKU-B", 0))), zero);
        orderTransactionalService.createOrderInternal(reply(nullQuantity, List.of(SNAP_A),
                List.of(new Item("SKU-A", null))), nullQuantity);

        assertFailed(zero, "INVALID_QUANTITY=[SKU-B]");
        assertFailed(nullQuantity, "INVALID_QUANTITY=[SKU-A]");
    }

    @Test
    @DisplayName("중복 SKU 이벤트 → 예외 없이 FAILED와 사유 저장, 주문 없음")
    void duplicateSku_marksFailed() {
        String key = pendingKey();

        orderTransactionalService.createOrderInternal(reply(key, List.of(SNAP_A, SNAP_A),
                List.of(new Item("SKU-A", 1), new Item("SKU-A", 2))), key);

        assertFailed(key, "DUPLICATE_SKU=[SKU-A]");
    }

    @Test
    @DisplayName("가격이 없는 스냅샷 → FAILED와 사유 저장, 주문 없음")
    void nullPrice_marksFailed() {
        String key = pendingKey();
        ProductSnapshotItem noPrice = new ProductSnapshotItem("SKU-A", 1L, "상품", 10L, null, Map.of());

        orderTransactionalService.createOrderInternal(reply(key, List.of(noPrice), List.of(new Item("SKU-A", 1))), key);

        assertFailed(key, "MISSING_PRICE=[SKU-A]");
    }

    @Test
    @DisplayName("이미 FAILED인 요청에 정상 응답이 와도 주문을 만들지 않는다")
    void failedRecord_ignoresLaterReply() {
        String key = pendingKey();
        orderTransactionalService.failOrder(key, "MISSING_SKU=[SKU-X]");

        orderTransactionalService.createOrderInternal(reply(key, List.of(SNAP_A), List.of(new Item("SKU-A", 1))), key);

        assertFailed(key, "MISSING_SKU=[SKU-X]");
    }

    @Test
    @DisplayName("이미 주문이 생성된 요청에 실패 응답이 와도 COMPLETED를 유지한다")
    void completedRecord_ignoresLaterFailure() {
        String key = pendingKey();
        orderTransactionalService.createOrderInternal(reply(key, List.of(SNAP_A), List.of(new Item("SKU-A", 1))), key);

        orderTransactionalService.failOrder(key, "MISSING_SKU=[SKU-X]");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("COMPLETED");
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("기록 없는 응답은 비즈니스 실패가 아니라 시스템 오류로 전파한다")
    void missingRecord_throws() {
        assertThatThrownBy(() -> orderTransactionalService.createOrderInternal(
                reply("no-record", List.of(SNAP_A), List.of(new Item("SKU-A", 1))), "no-record"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> orderTransactionalService.failOrder("no-record", "reason"))
                .isInstanceOf(DomainException.class);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("주문 생성 중 시스템 오류 → 주문·상세·saga가 모두 롤백되고 PENDING으로 남아 재시도로 완료된다")
    void systemFailure_rollsBackAndCanBeRetried() {
        String key = pendingKey();
        ProductSnapshotReplyResult reply = reply(key, List.of(SNAP_A), List.of(new Item("SKU-A", 1)));
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_outbox() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'outbox insert failed'; END; $$ LANGUAGE plpgsql
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER fail_outbox_insert BEFORE INSERT ON outbox_event
                FOR EACH ROW EXECUTE FUNCTION fail_outbox()
                """);
        try {
            assertThatThrownBy(() -> orderTransactionalService.createOrderInternal(reply, key))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER fail_outbox_insert ON outbox_event");
            jdbcTemplate.execute("DROP FUNCTION fail_outbox()");
        }

        assertThat(orderCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_saga_state", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_item", Long.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("PENDING");

        orderTransactionalService.createOrderInternal(reply, key);

        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("COMPLETED");
    }

    private void assertFailed(String key, String reason) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failure_reason FROM idempotency_request WHERE idem_key = ?", String.class, key)).isEqualTo(reason);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT order_id FROM idempotency_request WHERE idem_key = ?", Long.class, key)).isNull();
        assertThat(orderCount()).isZero();
        assertThat(outboxCount("order-create-event")).isZero();
    }

    private String pendingKey() {
        String key = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO idempotency_request (idem_key, request_hash, status) VALUES (?, ?, 'PENDING')",
                key, "h".repeat(64));
        return key;
    }

    private static ProductSnapshotItem snapshot(String sku, String price) {
        return new ProductSnapshotItem(sku, 1L, "상품", 10L, new BigDecimal(price), Map.of("color", "black"));
    }

    private ProductSnapshotReplyResult reply(String key, List<ProductSnapshotItem> items, List<Item> requestItems) {
        return new ProductSnapshotReplyResult(UUID.randomUUID(), null, true, null, items, requestItems, key, 1L,
                new ProductSnapshotReplyResult.ShippingAddress("홍길동", "서울"));
    }

    private long orderCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Long.class);
    }

    private long outboxCount(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ?", Long.class, eventType);
    }

    private void runConcurrently(List<Runnable> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable task : tasks) {
                futures.add(executor.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    task.run();
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(40, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
