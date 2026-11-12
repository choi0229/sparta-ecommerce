package org.teamsparta.orderapi.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventClaimer;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxStatusUpdater;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventRecorder;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxImmediatePublisher;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherJob;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

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
 * SENT 기록 비용 축소(배치 SENT, UPDATE … RETURNING claim, 조건부 SENT)를 실제 PostgreSQL에서 검증한다. Kafka 전송만 Mock이다.
 *
 * <ul>
 *   <li>폴러 배치에서 전송에 성공한 행만 한 번의 UPDATE로 SENT가 되고, 실패한 행은 건별로 재시도가 예약된다</li>
 *   <li>SENT 기록은 자신의 claim(claimed_at)인 PROCESSING 행에만 적용된다 — stale 복구 후 다시 claim된 행을 덮어쓰지 않는다</li>
 *   <li>재시도 예약도 같은 조건으로만 적용된다 — 이전 claim의 실패 기록이 다시 claim된 행을 PENDING으로 되돌리지 않는다</li>
 *   <li>즉시 발행용 claim은 별도 조회 없이 claim된 행을 돌려준다</li>
 * </ul>
 */
@Tag("integration")
@SpringBootTest(properties = {
        "scheduler.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class OutboxSentRecordIntegrationTest {

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

    @Autowired
    private OutboxPublisherJob outboxPublisherJob;

    @Autowired
    private OutboxEventClaimer outboxEventClaimer;

    @Autowired
    private OutboxStatusUpdater outboxStatusUpdater;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxEventRecorder outboxEventRecorder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationContext applicationContext;

    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockBean
    private KafkaAdmin kafkaAdmin;

    @AfterEach
    void cleanUp() {
        outboxEventRepository.deleteAll();
    }

    @Test
    @DisplayName("배치 SENT 처리: 전송에 성공한 행만 SENT가 되고, 실패한 행은 건별로 재시도가 예약된다")
    void batchSent_marksOnlySucceededRows() throws Exception {
        OutboxEvent first = savePending("a");
        OutboxEvent failing = savePending("b");
        OutboxEvent third = savePending("c");
        ZonedDateTime before = ZonedDateTime.now();
        given(kafkaTemplate.send(eq(TOPIC), anyString(), anyString()))
                .willReturn(CompletableFuture.completedFuture(null));
        given(kafkaTemplate.send(TOPIC, "b", PAYLOAD))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka Down")));

        outboxPublisherJob.publish();

        for (OutboxEvent sent : List.of(first, third)) {
            OutboxEvent row = find(sent);
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getSentAt()).isNotNull();
            assertThat(row.getRetryCount()).isZero();
        }
        OutboxEvent retry = find(failing);
        assertThat(retry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retry.getRetryCount()).isEqualTo(1);
        assertThat(retry.getSentAt()).isNull();
        // 첫 실패의 백오프: 1분 × 2^1 = 2분
        assertThat(retry.getNextRetryAt()).isAfter(before.plus(Duration.ofMinutes(2)).minusSeconds(1));
    }

    @Test
    @DisplayName("stale 복구 후 다시 claim된 행에는 이전 claim의 SENT 기록이 적용되지 않는다")
    void staleReclaimed_previousClaimCannotMarkSent() {
        OutboxEvent event = savePending("reclaimed");
        // 1차 claim (이 claim의 Publisher가 전송 후 오래 멈춘 상황)
        OutboxEvent firstClaim = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 10).get(0);
        ZonedDateTime firstClaimedAt = firstClaim.getClaimedAt();

        // stale 복구로 PENDING이 된 동안에는 이전 claim의 SENT 기록이 적용되지 않는다
        outboxEventClaimer.recoverStaleProcessing(ZonedDateTime.now().plusMinutes(1), 10);
        assertThat(outboxStatusUpdater.markSent(List.of(event.getId()), firstClaimedAt)).isZero();
        assertThat(find(event).getStatus()).isEqualTo(OutboxStatus.PENDING);

        // 다른 쪽(즉시 발행)이 다시 claim한 뒤에도 이전 claim의 SENT 기록은 적용되지 않는다
        OutboxEvent secondClaim = outboxEventClaimer.claimIfPending(event.getId(), ZonedDateTime.now()).orElseThrow();
        assertThat(secondClaim.getClaimedAt()).isNotEqualTo(firstClaimedAt);
        assertThat(outboxStatusUpdater.markSent(List.of(event.getId()), firstClaimedAt)).isZero();
        OutboxEvent stillClaimed = find(event);
        assertThat(stillClaimed.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(stillClaimed.getSentAt()).isNull();

        // 현재 claim의 기록만 적용된다
        assertThat(outboxStatusUpdater.markSent(List.of(event.getId()), secondClaim.getClaimedAt())).isEqualTo(1);
        assertThat(find(event).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("즉시 발행용 claim은 UPDATE … RETURNING으로 claim된 행을 돌려주고, PENDING이 아니면 empty다")
    void claimIfPending_returnsClaimedRowOnlyOnce() {
        OutboxEvent event = savePending("single");

        OutboxEvent claimed = outboxEventClaimer.claimIfPending(event.getId(), ZonedDateTime.now()).orElseThrow();

        assertThat(claimed.getId()).isEqualTo(event.getId());
        assertThat(claimed.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(claimed.getPayload()).isEqualTo(find(event).getPayload());
        assertThat(claimed.getClaimedAt().toInstant()).isEqualTo(find(event).getClaimedAt().toInstant());
        assertThat(outboxEventClaimer.claimIfPending(event.getId(), ZonedDateTime.now())).isEmpty();
    }

    @Test
    @DisplayName("stale 복구 후 다시 claim된 행에는 이전 claim의 재시도 예약이 적용되지 않는다")
    void staleReclaimed_previousClaimCannotScheduleRetry() {
        OutboxEvent event = savePending("reclaimed-failed");
        // 1차 claim (이 claim의 Publisher가 전송 실패 후 오래 멈춘 상황)
        OutboxEvent firstClaim = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 10).get(0);
        ZonedDateTime firstClaimedAt = firstClaim.getClaimedAt();

        // stale 복구로 PENDING이 된 동안에는 이전 claim의 실패 기록이 적용되지 않는다
        outboxEventClaimer.recoverStaleProcessing(ZonedDateTime.now().plusMinutes(1), 10);
        assertThat(outboxStatusUpdater.markFailed(event.getId(), firstClaimedAt)).isFalse();
        OutboxEvent recovered = find(event);
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getRetryCount()).isZero();
        assertThat(recovered.getNextRetryAt()).isNull();

        // 다른 쪽(즉시 발행)이 다시 claim한 뒤에도 이전 claim의 실패 기록은 그 claim을 PENDING으로 되돌리지 않는다
        OutboxEvent secondClaim = outboxEventClaimer.claimIfPending(event.getId(), ZonedDateTime.now()).orElseThrow();
        assertThat(secondClaim.getClaimedAt()).isNotEqualTo(firstClaimedAt);
        assertThat(outboxStatusUpdater.markFailed(event.getId(), firstClaimedAt)).isFalse();
        OutboxEvent stillClaimed = find(event);
        assertThat(stillClaimed.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(stillClaimed.getRetryCount()).isZero();
        assertThat(stillClaimed.getNextRetryAt()).isNull();
        assertThat(stillClaimed.getClaimedAt().toInstant()).isEqualTo(secondClaim.getClaimedAt().toInstant());

        // 현재 claim의 실패 기록만 적용된다
        assertThat(outboxStatusUpdater.markFailed(event.getId(), secondClaim.getClaimedAt())).isTrue();
        OutboxEvent retry = find(event);
        assertThat(retry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(retry.getRetryCount()).isEqualTo(1);
        assertThat(retry.getNextRetryAt()).isNotNull();
    }

    private OutboxEvent savePending(String aggregateId) {
        return outboxEventRepository.save(OutboxEvent.pending("Orders", aggregateId, TOPIC, PAYLOAD));
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
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
