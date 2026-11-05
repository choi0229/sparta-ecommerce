package org.teamsparta.orderapi.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherJob;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 PostgreSQL 행 잠금과 Spring 트랜잭션 프록시를 거쳐 OutboxPublisherJob.publish()를 검증한다.
 * Kafka 전송만 Mock이고, Outbox 조회·잠금·상태 갱신은 실제 DB에서 수행된다.
 *
 * <p>잡아내는 결함: 잠금을 보유한 트랜잭션 안에서 상태 갱신이 별도 트랜잭션으로 같은 행을 수정하면
 * Publisher가 끝나지 않는다. 이때 대기 관계를 실패 메시지에 남겨 원인을 구분할 수 있게 한다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class OutboxPublisherLockIntegrationTest {

    private static final long PUBLISH_TIMEOUT_SECONDS = 15;
    private static final String TOPIC = "order-create-event";

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
    private OutboxPublisherJob outboxPublisherJob;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("Kafka 전송 성공 — Publisher가 제한 시간 안에 끝나고 배치의 모든 이벤트가 SENT가 된다")
    void publish_kafkaSucceeds_allEventsBecomeSent() throws Exception {
        OutboxEvent first = savePending("1");
        OutboxEvent second = savePending("2");
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(null));

        publishWithinTimeout();

        assertSent(first.getId());
        assertSent(second.getId());
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("Kafka 전송 실패 — Publisher가 제한 시간 안에 끝나고 이벤트가 재시도 대기 상태가 된다")
    void publish_kafkaFails_eventIsScheduledForRetry() throws Exception {
        OutboxEvent event = savePending("1");
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")));

        ZonedDateTime before = ZonedDateTime.now();
        publishWithinTimeout();
        ZonedDateTime after = ZonedDateTime.now();

        OutboxEvent saved = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getSentAt()).isNull();
        // 첫 실패의 백오프: 기본 1분 × 2^1 = 2분 (DB 시각 정밀도를 고려해 1초 여유)
        assertThat(saved.getNextRetryAt())
                .isAfter(before.plus(Duration.ofMinutes(2)).minusSeconds(1))
                .isBefore(after.plus(Duration.ofMinutes(2)).plusSeconds(1));

        // 대기 시간이 지나기 전에는 다시 발행하지 않는다
        publishWithinTimeout();
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), anyString(), anyString());
    }

    @Test
    @DisplayName("같은 배치의 전송 실패는 다른 이벤트의 SENT 전이를 되돌리지 않는다")
    void publish_oneEventFails_otherEventStillBecomesSent() throws Exception {
        OutboxEvent failing = savePending("fail");
        OutboxEvent succeeding = savePending("ok");
        given(kafkaTemplate.send(TOPIC, "fail", "{\"orderId\": 1}"))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")));
        given(kafkaTemplate.send(TOPIC, "ok", "{\"orderId\": 1}"))
                .willReturn(CompletableFuture.completedFuture(null));

        publishWithinTimeout();

        OutboxEvent failed = outboxEventRepository.findById(failing.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertSent(succeeding.getId());
    }

    private OutboxEvent savePending(String aggregateId) {
        return outboxEventRepository.save(
                OutboxEvent.pending("Orders", aggregateId, TOPIC, "{\"orderId\": 1}"));
    }

    private void assertSent(Long id) {
        OutboxEvent saved = outboxEventRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(saved.getSentAt()).isNotNull();
        assertThat(saved.getNextRetryAt()).isNull();
    }

    private void publishWithinTimeout() throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            outboxPublisherJob.publish();
            return null;
        });
        Thread publisher = new Thread(task, "outbox-publisher-under-test");
        publisher.setDaemon(true);
        publisher.start();

        try {
            task.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            String diagnostics = describeHang(publisher);
            System.out.println(diagnostics);
            terminateOtherBackends();
            awaitPublisherExit(task);
            fail("publish()가 " + PUBLISH_TIMEOUT_SECONDS + "초 안에 끝나지 않았다.\n" + diagnostics);
        }
    }

    private void awaitPublisherExit(FutureTask<Void> task) throws InterruptedException {
        try {
            task.get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException ignored) {
            // 강제로 끊은 커넥션 때문에 실패하거나 남아 있어도 데몬 스레드라 테스트를 막지 않는다
        }
    }

    private String describeHang(Thread publisher) throws SQLException {
        String stack = Arrays.stream(publisher.getStackTrace())
                .map(StackTraceElement::toString)
                .filter(frame -> frame.contains("org.teamsparta") || frame.contains("PgConnection")
                        || frame.contains("commit") || frame.contains("SocketInputStream"))
                .collect(Collectors.joining("\n  "));

        try (Connection connection = openDiagnosticConnection()) {
            return "\n===== [OUTBOX HANG DIAGNOSTICS] =====\n"
                    + "[blocked -> blocking]\n" + query(connection, """
                        SELECT w.pid AS waiting_pid, w.wait_event_type, w.wait_event, w.state AS waiting_state,
                               left(w.query, 90) AS waiting_query,
                               b.pid AS blocking_pid, b.state AS blocking_state,
                               left(b.query, 90) AS blocking_last_query
                        FROM pg_stat_activity w
                        CROSS JOIN LATERAL unnest(pg_blocking_pids(w.pid)) AS blocker(pid)
                        JOIN pg_stat_activity b ON b.pid = blocker.pid
                        WHERE w.datname = current_database()
                        """)
                    + "[locks]\n" + query(connection, """
                        SELECT l.pid, l.locktype, l.relation::regclass AS relation, l.tuple, l.mode, l.granted
                        FROM pg_locks l
                        JOIN pg_stat_activity a ON a.pid = l.pid
                        WHERE a.datname = current_database()
                          AND a.pid <> pg_backend_pid()
                          AND (NOT l.granted OR l.relation = 'outbox_event'::regclass OR l.locktype = 'transactionid')
                        ORDER BY l.granted, l.pid
                        """)
                    + "[outbox rows, as committed]\n" + query(connection,
                        "SELECT id, status, retry_count, sent_at FROM outbox_event ORDER BY id")
                    + "[publisher thread: " + publisher.getState() + "]\n  " + stack
                    + "\n=====================================";
        }
    }

    private void terminateOtherBackends() throws SQLException {
        try (Connection connection = openDiagnosticConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                    """);
        }
    }

    private Connection openDiagnosticConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private String query(Connection connection, String sql) throws SQLException {
        StringBuilder out = new StringBuilder();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            ResultSetMetaData meta = rows.getMetaData();
            while (rows.next()) {
                out.append("  ");
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    out.append(meta.getColumnLabel(i)).append('=').append(rows.getString(i)).append(" | ");
                }
                out.append('\n');
            }
        }
        return out.length() == 0 ? "  (none)\n" : out.toString();
    }
}
