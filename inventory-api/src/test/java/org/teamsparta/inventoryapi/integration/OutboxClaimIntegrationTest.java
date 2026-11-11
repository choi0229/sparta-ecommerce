package org.teamsparta.inventoryapi.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxEventClaimer;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxPublisherJob;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * claim 기반 Outbox 발행을 실제 PostgreSQL에서 검증한다. Kafka 전송만 Mock이다.
 *
 * <ul>
 *   <li>Kafka 전송 중에는 Outbox 행 잠금이 없다</li>
 *   <li>여러 Publisher가 동시에 claim해도 같은 행이 두 번 claim되지 않는다 (FOR UPDATE SKIP LOCKED)</li>
 *   <li>전송 실패는 재시도로 예약되고, 재시도 횟수는 롤백되지 않고 누적된다</li>
 *   <li>claim 후 SENT 처리 전에 중단된 행은 stale 복구 후 다시 발행된다 (At-Least-Once)</li>
 * </ul>
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OutboxClaimIntegrationTest extends InventoryIntegrationTestSupport {

    private static final String TOPIC = "inventory-reserved-event";
    private static final String PAYLOAD = "{\"orderId\": 1}";

    @Autowired
    private OutboxPublisherJob outboxPublisherJob;

    @Autowired
    private OutboxEventClaimer outboxEventClaimer;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private KafkaAdmin kafkaAdmin;

    @BeforeEach
    void cleanBefore() {
        // 컨테이너를 다른 통합 테스트 클래스와 공유하므로 남은 Outbox 행을 지우고 시작한다
        outboxEventRepository.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Kafka 전송 중에는 Outbox 행 잠금이 없어 다른 트랜잭션이 같은 행을 바로 갱신할 수 있다")
    void send_holdsNoRowLock() throws Exception {
        OutboxEvent event = savePending("1");
        AtomicReference<Object> updateDuringSend = new AtomicReference<>();
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString())).willAnswer(invocation -> {
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                statement.execute("SET lock_timeout = '500ms'");
                updateDuringSend.set(statement.executeUpdate(
                        "UPDATE outbox_event SET retry_count = retry_count WHERE id = " + event.getId()));
            } catch (SQLException e) {
                updateDuringSend.set(e);
            }
            return CompletableFuture.completedFuture(null);
        });

        outboxPublisherJob.publish();

        assertThat(updateDuringSend.get()).as("전송 중 같은 행 갱신 결과(잠금 대기면 SQLException)").isEqualTo(1);
        assertThat(find(event).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 행은 기다리지 않고 건너뛰고 나머지를 claim한다 (SKIP LOCKED)")
    void claim_skipsLockedRows() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(savePending("k" + i).getId());
        }
        try (Connection locker = openConnection()) {
            locker.setAutoCommit(false);
            try (Statement statement = locker.createStatement()) {
                statement.executeQuery("SELECT id FROM outbox_event WHERE id IN (" + ids.get(0) + "," + ids.get(1)
                        + ") FOR UPDATE");
            }

            long start = System.nanoTime();
            List<OutboxEvent> claimed = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 10);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(elapsedMs).as("잠긴 행을 기다리지 않아야 한다").isLessThan(1000);
            assertThat(claimed).extracting(OutboxEvent::getId).containsExactlyInAnyOrderElementsOf(ids.subList(2, 6));
            locker.rollback();
        }
        assertThat(find(ids.get(0)).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(find(ids.get(2)).getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(find(ids.get(2)).getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("여러 Publisher가 동시에 claim해도 같은 행이 두 번 claim되지 않는다")
    void concurrentClaim_noDuplicates() throws Exception {
        int rows = 300;
        for (int i = 0; i < rows; i++) {
            savePending("c" + i);
        }
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> results = new ArrayList<>();
        for (int w = 0; w < workers; w++) {
            results.add(pool.submit((Callable<List<Long>>) () -> {
                start.await();
                List<Long> mine = new ArrayList<>();
                while (true) {
                    List<OutboxEvent> batch = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 7);
                    if (batch.isEmpty()) {
                        return mine;
                    }
                    batch.forEach(e -> mine.add(e.getId()));
                }
            }));
        }
        start.countDown();
        List<Long> all = Collections.synchronizedList(new ArrayList<>());
        for (Future<List<Long>> result : results) {
            all.addAll(result.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        Set<Long> unique = new HashSet<>(all);
        assertThat(all).as("claim된 전체 건수").hasSize(rows);
        assertThat(unique).as("중복 claim 없음").hasSize(rows);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.PROCESSING)).isEqualTo(rows);
    }

    @Test
    @DisplayName("전송 실패는 재시도로 예약되고, 재시도 횟수는 롤백되지 않고 누적된다")
    void sendFailure_retryScheduledAndCountAccumulates() throws Exception {
        OutboxEvent event = savePending("1");
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")));

        outboxPublisherJob.publish();

        OutboxEvent first = find(event);
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(first.getNextRetryAt()).isAfter(ZonedDateTime.now().plusMinutes(1));

        // 재시도 시각이 지난 것으로 만들고 다시 실패시킨다
        execute("UPDATE outbox_event SET next_retry_at = now() - interval '1 second' WHERE id = " + event.getId());
        ZonedDateTime before = ZonedDateTime.now();
        outboxPublisherJob.publish();

        OutboxEvent second = find(event);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(second.getRetryCount()).isEqualTo(2);
        // 두 번째 실패의 백오프: 1분 × 2^2 = 4분
        assertThat(second.getNextRetryAt()).isAfter(before.plus(Duration.ofMinutes(4)).minusSeconds(1));
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("claim 후 SENT 처리 전에 중단된 행은 stale 복구 후 다시 발행된다")
    void staleProcessing_isRecoveredAndRepublished() throws Exception {
        OutboxEvent crashed = savePending("crashed");
        OutboxEvent inFlight = savePending("in-flight");
        // 두 행을 claim만 하고 전송하지 않는다 (Publisher 중단을 흉내 낸다)
        assertThat(outboxEventClaimer.claimBatch(ZonedDateTime.now(), 10)).hasSize(2);
        // crashed만 stale 기준(기본 5분)보다 오래전에 claim된 것으로 만든다
        execute("UPDATE outbox_event SET claimed_at = now() - interval '10 minutes' WHERE id = " + crashed.getId());
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(null));

        outboxPublisherJob.publish();

        verify(kafkaTemplate, times(1)).send(TOPIC, "crashed", PAYLOAD);
        verify(kafkaTemplate, never()).send(TOPIC, "in-flight", PAYLOAD);
        OutboxEvent recovered = find(crashed);
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(recovered.getRetryCount()).as("publisher 중단은 전송 실패가 아니다").isZero();
        assertThat(find(inFlight).getStatus()).as("timeout 전 PROCESSING은 그대로").isEqualTo(OutboxStatus.PROCESSING);
    }

    private OutboxEvent savePending(String aggregateId) {
        return outboxEventRepository.save(OutboxEvent.pending("Inventory", aggregateId, TOPIC, PAYLOAD));
    }

    private OutboxEvent find(OutboxEvent event) {
        return find(event.getId());
    }

    private OutboxEvent find(Long id) {
        return outboxEventRepository.findById(id).orElseThrow();
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
