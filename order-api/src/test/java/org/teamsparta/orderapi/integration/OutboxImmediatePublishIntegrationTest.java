package org.teamsparta.orderapi.integration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventClaimer;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventRecorded;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventRecorder;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventSender;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxImmediatePublisher;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherJob;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxStatusUpdater;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 커밋 직후 발행(outbox.immediate-publish.enabled=true)을 실제 PostgreSQL에서 검증한다. Kafka 전송만 Mock이다.
 * 폴러 주기는 1시간으로 길게 두어(기동 직후 한 번만 실행), 발행이 폴러 주기를 기다리지 않음을 확인한다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "outbox.immediate-publish.enabled=true",
        "outbox.immediate-publish.threads=2",
        "scheduler.outbox.fixed-delay-ms=3600000",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OutboxImmediatePublishIntegrationTest {

    private static final String TOPIC = "order-create-event";
    private static final String PAYLOAD = "{\"orderId\": 1}";

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

    @Autowired private OutboxEventRecorder outboxEventRecorder;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPublisherJob outboxPublisherJob;
    @Autowired private OutboxEventClaimer outboxEventClaimer;
    @Autowired private OutboxStatusUpdater outboxStatusUpdater;
    @Autowired private OutboxEventSender outboxEventSender;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private KafkaTemplate<String, String> kafkaTemplate;
    @MockBean private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        Mockito.reset(kafkaTemplate);
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("커밋 직후 폴러 주기(1시간)를 기다리지 않고 발행되어 SENT가 된다")
    void publishedRightAfterCommit() {
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(null));

        long start = System.nanoTime();
        Long id = recordInTransaction("now");

        awaitTrue(() -> status(id) == OutboxStatus.SENT, 3000);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
        verify(kafkaTemplate, times(1)).send(TOPIC, "now", PAYLOAD);
    }

    @Test
    @DisplayName("롤백된 트랜잭션의 Outbox 행은 즉시 발행되지 않는다")
    void rolledBackTransaction_isNotPublished() throws Exception {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            outboxEventRecorder.save(OutboxEvent.pending("Orders", "rolled-back", TOPIC, PAYLOAD));
            status.setRollbackOnly();
        });

        Thread.sleep(1000);
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(outboxEventRepository.count()).isZero();
    }

    @Test
    @DisplayName("즉시 발행과 폴러가 같은 행을 동시에 처리해도 행마다 전송은 1번이다")
    void immediateAndPollerConcurrently_sendOncePerRow() throws Exception {
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString())).willAnswer(invocation -> {
            Thread.sleep(1); // 경합 구간을 넓힌다
            return CompletableFuture.completedFuture(null);
        });
        int rows = 200;
        AtomicBoolean producing = new AtomicBoolean(true);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> producers = new ArrayList<>();
        for (int w = 0; w < 4; w++) {
            int worker = w;
            producers.add(pool.submit(() -> {
                start.await();
                for (int i = worker; i < rows; i += 4) {
                    recordInTransaction("k" + i);
                }
                return null;
            }));
        }
        Future<?> poller = pool.submit(() -> {
            start.await();
            while (producing.get() || outboxEventRepository.countByStatus(OutboxStatus.SENT) < rows) {
                outboxPublisherJob.publish();
            }
            return null;
        });
        start.countDown();
        for (Future<?> producer : producers) {
            producer.get(60, TimeUnit.SECONDS);
        }
        producing.set(false);
        poller.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        Map<Object, Long> sendsPerKey = Mockito.mockingDetails(kafkaTemplate).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("send"))
                .collect(Collectors.groupingBy(i -> i.getArgument(1), Collectors.counting()));
        assertThat(sendsPerKey).hasSize(rows);
        assertThat(sendsPerKey.values()).allMatch(count -> count == 1L);
        assertThat(outboxEventRepository.countByStatus(OutboxStatus.SENT)).isEqualTo(rows);
    }

    @Test
    @DisplayName("즉시 발행 중 Kafka 실패 → 재시도 예약 → 재시도 시각이 되면 폴러가 다시 발행한다")
    void immediateFailure_retryScheduled_thenPollerRepublishes() throws Exception {
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka down")));

        Long id = recordInTransaction("retry");
        awaitTrue(() -> retryCount(id) == 1, 3000);
        OutboxEvent failed = outboxEventRepository.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getNextRetryAt()).isNotNull();

        // 재시도 대기 중에는 폴러도 발행하지 않는다
        outboxPublisherJob.publish();
        verify(kafkaTemplate, times(1)).send(TOPIC, "retry", PAYLOAD);

        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(null));
        execute("UPDATE outbox_event SET next_retry_at = now() - interval '1 second' WHERE id = " + id);
        outboxPublisherJob.publish();

        assertThat(status(id)).isEqualTo(OutboxStatus.SENT);
        verify(kafkaTemplate, times(2)).send(TOPIC, "retry", PAYLOAD);
    }

    @Test
    @DisplayName("실행기 큐가 가득 차면 건너뛰고(skipped), 남은 행은 폴러가 발행해 유실이 없다")
    void executorSaturated_skippedRowsArePublishedByPoller() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString())).willAnswer(invocation -> {
            if ("first".equals(invocation.getArgument(1))) {
                release.await(10, TimeUnit.SECONDS); // 유일한 실행기 스레드를 붙잡는다
            }
            return CompletableFuture.completedFuture(null);
        });
        // 스레드 1개, 큐 1칸짜리 즉시 발행기. AFTER_COMMIT 리스너가 하는 일(onRecorded)을 직접 호출한다
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxImmediatePublisher small = new OutboxImmediatePublisher(
                outboxEventClaimer, outboxStatusUpdater, outboxEventSender, registry, 1, 1);
        try {
            Long first = outboxEventRepository.save(OutboxEvent.pending("Orders", "first", TOPIC, PAYLOAD)).getId();
            Long second = outboxEventRepository.save(OutboxEvent.pending("Orders", "second", TOPIC, PAYLOAD)).getId();
            Long third = outboxEventRepository.save(OutboxEvent.pending("Orders", "third", TOPIC, PAYLOAD)).getId();

            small.onRecorded(new OutboxEventRecorded(first));   // 실행 중(전송에서 대기)
            awaitTrue(() -> status(first) == OutboxStatus.PROCESSING, 3000);
            small.onRecorded(new OutboxEventRecorded(second));  // 큐 1칸
            small.onRecorded(new OutboxEventRecorded(third));   // 큐 가득 참 → 건너뜀

            assertThat(registry.get("order.outbox.immediate").tag("result", "skipped").counter().count())
                    .isEqualTo(1.0);
            assertThat(status(third)).isEqualTo(OutboxStatus.PENDING);

            release.countDown();
            awaitTrue(() -> status(first) == OutboxStatus.SENT && status(second) == OutboxStatus.SENT, 5000);
            outboxPublisherJob.publish(); // 건너뛴 행은 폴러가 발행한다

            assertThat(status(third)).isEqualTo(OutboxStatus.SENT);
            for (String key : List.of("first", "second", "third")) {
                verify(kafkaTemplate, times(1)).send(TOPIC, key, PAYLOAD);
            }
        } finally {
            release.countDown();
            small.destroy();
        }
    }

    private Long recordInTransaction(String aggregateId) {
        return new TransactionTemplate(transactionManager).execute(status ->
                outboxEventRecorder.save(OutboxEvent.pending("Orders", aggregateId, TOPIC, PAYLOAD)).getId());
    }

    private OutboxStatus status(Long id) {
        return outboxEventRepository.findById(id).orElseThrow().getStatus();
    }

    private int retryCount(Long id) {
        return outboxEventRepository.findById(id).orElseThrow().getRetryCount();
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("조건이 " + timeoutMs + "ms 안에 충족되지 않았다");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
