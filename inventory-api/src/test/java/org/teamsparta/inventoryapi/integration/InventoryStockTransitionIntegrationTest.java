package org.teamsparta.inventoryapi.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderConfirmResult;
import org.teamsparta.inventoryapi.domain.inventory.event.dto.OrderCreateResult;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryReservationRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.InventoryStockRepository;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryExpireService;
import org.teamsparta.inventoryapi.domain.inventory.service.InventoryService;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 확정·해제의 상태 전이를 실제 PostgreSQL의 잠금과 CHECK 제약 아래에서 검증한다.
 * 동시성 테스트는 타이밍에 맡기지 않고, 별도 커넥션으로 잠금을 잡아 대기 상태를 만든 뒤 풀어 준다.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class InventoryStockTransitionIntegrationTest extends InventoryIntegrationTestSupport {

    private static final AtomicLong ORDER_IDS = new AtomicLong(1000);
    private static final String CONFIRM_FAILED = "inventory-confirm-failed-event";

    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private InventoryExpireService inventoryExpireService;
    @Autowired
    private InventoryStockRepository inventoryStockRepository;
    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM inventory_reservation");
        jdbcTemplate.update("DELETE FROM inventory_stock");
    }

    @Test
    @DisplayName("total=10, reserved=10에서 10개 확정 → total=0, reserved=0, 예약 CONFIRMED")
    void confirm_allReserved_decreasesTotalAndReserved() {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 10);
        assertStock(sku, 10, 10);

        inventoryService.onInventoryConfirmed(confirmRequest(reservation));

        assertStock(sku, 0, 0);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxCount("inventory-confirm-event", reservation)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 확정 이벤트 2회 → 두 번째는 재고와 Outbox를 바꾸지 않는다")
    void confirm_duplicateEvent_changesNothingTheSecondTime() {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 4);

        inventoryService.onInventoryConfirmed(confirmRequest(reservation));
        assertStock(sku, 6, 0);

        inventoryService.onInventoryConfirmed(confirmRequest(reservation));

        assertStock(sku, 6, 0);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxCount("inventory-confirm-event", reservation)).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 → total은 그대로, reserved만 감소, 예약 EXPIRED")
    void expire_releasesReservedOnly() {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 10);

        inventoryExpireService.expireOne(reservation.getId());

        assertStock(sku, 10, 0);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("이미 확정된 예약은 만료 처리되어도 재고가 변하지 않는다")
    void expire_afterConfirm_changesNothing() {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 4);
        inventoryService.onInventoryConfirmed(confirmRequest(reservation));

        inventoryExpireService.expireOne(reservation.getId());

        assertStock(sku, 6, 0);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxCount("inventory-expired-event", reservation)).isZero();
    }

    @Test
    @DisplayName("이미 만료된 예약에 확정이 오면 재고를 바꾸지 않고 확정 실패 이벤트를 Outbox에 저장한다")
    void confirm_afterExpire_changesNoStockAndSavesFailureEvent() throws Exception {
        String sku = saveStock(10);
        InventoryReservation expired = reserve(sku, 4);
        InventoryReservation other = reserve(sku, 3);
        inventoryExpireService.expireOne(expired.getId());
        assertStock(sku, 10, 3);

        inventoryService.onInventoryConfirmed(confirmRequest(expired));

        assertStock(sku, 10, 3);
        assertThat(statusOf(expired)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(statusOf(other)).isEqualTo(ReservationStatus.RESERVED);
        assertThat(outboxCount("inventory-confirm-event", expired)).isZero();
        assertThat(outboxCount(CONFIRM_FAILED, expired)).isEqualTo(1);

        JsonNode payload = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = ?", String.class, CONFIRM_FAILED));
        assertThat(payload.get("orderId").asLong()).isEqualTo(expired.getOrderId());
        assertThat(payload.get("sagaId").asText()).isEqualTo(expired.getSagaId().toString());
        assertThat(payload.get("reservationId").asText()).isEqualTo(expired.getId().toString());
        assertThat(payload.get("reservationStatus").asText()).isEqualTo("EXPIRED");
        assertThat(payload.hasNonNull("eventId")).isTrue();
    }

    @Test
    @DisplayName("만료된 예약에 확정 요청이 반복되면 재고는 그대로이고 실패 이벤트는 요청마다 저장된다")
    void confirm_repeatedAfterExpire_savesFailureEventPerRequest() {
        String sku = saveStock(10);
        InventoryReservation expired = reserve(sku, 4);
        inventoryExpireService.expireOne(expired.getId());

        inventoryService.onInventoryConfirmed(confirmRequest(expired));
        inventoryService.onInventoryConfirmed(confirmRequest(expired));

        assertStock(sku, 10, 0);
        assertThat(outboxCount(CONFIRM_FAILED, expired)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 예약에 확정이 먼저 잠금을 얻으면 → 확정만 재고를 변경하고 만료는 아무것도 하지 않는다")
    void confirmAndExpire_confirmAcquiresLockFirst() throws Exception {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 3);

        runBothWhileReservationIsLocked(reservation,
                () -> inventoryService.onInventoryConfirmed(confirmRequest(reservation)),
                () -> inventoryExpireService.expireOne(reservation.getId()));

        assertStock(sku, 7, 0);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(outboxCount("inventory-confirm-event", reservation)).isEqualTo(1);
        assertThat(outboxCount("inventory-expired-event", reservation)).isZero();
        assertThat(outboxCount(CONFIRM_FAILED, reservation)).isZero();
    }

    @Test
    @DisplayName("같은 예약에 만료가 먼저 잠금을 얻으면 → 만료만 재고를 변경하고 확정은 재고를 바꾸지 않는다")
    void confirmAndExpire_expireAcquiresLockFirst() throws Exception {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 3);

        runBothWhileReservationIsLocked(reservation,
                () -> inventoryExpireService.expireOne(reservation.getId()),
                () -> inventoryService.onInventoryConfirmed(confirmRequest(reservation)));

        assertStock(sku, 10, 0);
        assertThat(statusOf(reservation)).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(outboxCount("inventory-expired-event", reservation)).isEqualTo(1);
        assertThat(outboxCount("inventory-confirm-event", reservation)).isZero();
        assertThat(outboxCount(CONFIRM_FAILED, reservation)).isEqualTo(1);
    }

    @RepeatedTest(20)
    @DisplayName("확정과 만료를 잠금 없이 동시에 실행해도 둘 중 하나만 재고를 변경한다")
    void confirmAndExpire_freeRace_exactlyOneChangesStock() throws Exception {
        String sku = saveStock(10);
        InventoryReservation reservation = reserve(sku, 3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirm = executor.submit(() -> {
                await(start);
                inventoryService.onInventoryConfirmed(confirmRequest(reservation));
            });
            Future<?> expire = executor.submit(() -> {
                await(start);
                inventoryExpireService.expireOne(reservation.getId());
            });
            start.countDown();
            confirm.get(30, TimeUnit.SECONDS);
            expire.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        ReservationStatus winner = statusOf(reservation);
        assertThat(winner).isIn(ReservationStatus.CONFIRMED, ReservationStatus.EXPIRED);
        if (winner == ReservationStatus.CONFIRMED) {
            assertStock(sku, 7, 0);
            assertThat(outboxCount(CONFIRM_FAILED, reservation)).isZero();
        } else {
            assertStock(sku, 10, 0);
            assertThat(outboxCount(CONFIRM_FAILED, reservation)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("재고 잠금은 물리 저장 순서와 무관하게 SKU 순서로 획득된다")
    void stockLocks_areAcquiredInSkuOrder() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String skuA = "A-" + suffix;
        String skuB = "B-" + suffix;
        // B를 먼저 저장해, 정렬이 없으면 물리 순서상 B부터 잠그도록 만든다
        inventoryStockRepository.save(InventoryStock.create(skuB, 10));
        inventoryStockRepository.save(InventoryStock.create(skuA, 10));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = openConnection(); Connection probe = openConnection()) {
            holder.setAutoCommit(false);
            selectForUpdate(holder, skuB);

            Future<?> locking = executor.submit(() -> transactionTemplate.executeWithoutResult(status ->
                    inventoryStockRepository.findAllSkuInForUpdate(List.of(skuA, skuB))));
            awaitLockWaiters(1);

            // B에서 대기 중일 때 A가 이미 잠겨 있어야 A → B 순서로 잠근 것이다
            assertThat(isLocked(probe, skuA))
                    .as("B를 기다리는 동안 A가 먼저 잠겨 있어야 한다")
                    .isTrue();

            holder.commit();
            locking.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 SKU들을 반대 순서로 요청하는 동시 예약에서 교착 없이 모두 예약된다")
    void reserve_multipleSkusInOppositeOrder_noDeadlock() throws Exception {
        String skuA = saveStock(100);
        String skuB = saveStock(100);
        int orders = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        long deadlocksBefore = deadlockCount();
        try {
            for (int i = 0; i < orders; i++) {
                List<OrderCreateResult.Item> items = i % 2 == 0
                        ? List.of(new OrderCreateResult.Item(skuA, 1), new OrderCreateResult.Item(skuB, 1))
                        : List.of(new OrderCreateResult.Item(skuB, 1), new OrderCreateResult.Item(skuA, 1));
                long orderId = ORDER_IDS.incrementAndGet();
                futures.add(executor.submit(() -> {
                    await(start);
                    inventoryService.reserveInventory(new OrderCreateResult(
                            UUID.randomUUID(), "order.created", orderId, UUID.randomUUID(), 1L, items));
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(40, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertStock(skuA, 100, orders);
        assertStock(skuB, 100, orders);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'inventory-reserved-event'", Long.class))
                .isEqualTo((long) orders);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'inventory-failed-event'", Long.class))
                .isZero();
        assertThat(deadlockCount()).isEqualTo(deadlocksBefore);
    }

    private void runBothWhileReservationIsLocked(InventoryReservation reservation, Runnable first, Runnable second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection holder = openConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement statement = holder.prepareStatement(
                    "SELECT id FROM inventory_reservation WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, reservation.getId());
                statement.executeQuery();
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

    private String saveStock(int total) {
        String sku = "SKU-" + UUID.randomUUID().toString().substring(0, 8);
        inventoryStockRepository.save(InventoryStock.create(sku, total));
        return sku;
    }

    private InventoryReservation reserve(String sku, int quantity) {
        long orderId = ORDER_IDS.incrementAndGet();
        inventoryService.reserveInventory(new OrderCreateResult(
                UUID.randomUUID(), "order.created", orderId, UUID.randomUUID(), 1L,
                List.of(new OrderCreateResult.Item(sku, quantity))));
        return inventoryReservationRepository.findByOrderId(orderId).orElseThrow();
    }

    private OrderConfirmResult confirmRequest(InventoryReservation reservation) {
        return new OrderConfirmResult(UUID.randomUUID(), "order.confirm",
                reservation.getOrderId(), reservation.getSagaId(), reservation.getId());
    }

    private void assertStock(String sku, int total, int reserved) {
        InventoryStock stock = inventoryStockRepository.findById(sku).orElseThrow();
        assertThat(stock.getTotalQuantity()).as("totalQuantity").isEqualTo(total);
        assertThat(stock.getReservedQuantity()).as("reservedQuantity").isEqualTo(reserved);
    }

    private ReservationStatus statusOf(InventoryReservation reservation) {
        return inventoryReservationRepository.findById(reservation.getId()).orElseThrow().getStatus();
    }

    private long outboxCount(String eventType, InventoryReservation reservation) {
        // 만료 이벤트의 aggregate_id는 예약 ID, 그 외 이벤트는 주문 ID다
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = ? AND aggregate_id IN (?, ?)",
                Long.class, eventType, reservation.getId().toString(), reservation.getOrderId().toString());
    }

    private long deadlockCount() {
        return jdbcTemplate.queryForObject(
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }

    private void awaitLockWaiters(int expected) {
        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(20, TimeUnit.MILLISECONDS).until(() ->
                jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """, Long.class) >= expected);
    }

    private void selectForUpdate(Connection connection, String sku) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sku FROM inventory_stock WHERE sku = ? FOR UPDATE")) {
            statement.setString(1, sku);
            statement.executeQuery();
        }
    }

    private boolean isLocked(Connection connection, String sku) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sku FROM inventory_stock WHERE sku = ? FOR UPDATE SKIP LOCKED")) {
            statement.setString(1, sku);
            try (ResultSet rows = statement.executeQuery()) {
                return !rows.next();
            }
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
