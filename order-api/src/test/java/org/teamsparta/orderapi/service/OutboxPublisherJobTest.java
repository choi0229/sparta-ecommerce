package org.teamsparta.orderapi.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventClaimer;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventSender;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherJob;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxStatusUpdater;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class OutboxPublisherJobTest {

    private OutboxPublisherJob outboxPublisherJob;

    @Mock
    private OutboxEventClaimer outboxEventClaimer;
    @Mock
    private OutboxStatusUpdater outboxStatusUpdater;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEvent event;
    private final String AGGREGATE_ID = "100";

    @BeforeEach
    void setUp() {
        outboxPublisherJob = new OutboxPublisherJob(
                outboxEventClaimer, outboxStatusUpdater, new OutboxEventSender(kafkaTemplate), new SimpleMeterRegistry(), 50, 300_000L);
        event = OutboxEvent.pending("Orders", AGGREGATE_ID, "order-create-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "retryCount", 0);
        ReflectionTestUtils.setField(event, "status", OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("성공: Kafka 전송 성공 시 OutboxStatusUpdater.markSent(ids, claimedAt)가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_Success_callsMarkSent() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("order-create-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markSent(eq(List.of(1L)), any());
        then(outboxStatusUpdater).should(never()).markFailed(anyLong(), any());
        then(kafkaTemplate).should(times(1)).send("order-create-event", AGGREGATE_ID, "{}");
    }

    @Test
    @DisplayName("실패: Kafka 전송 실패 시 OutboxStatusUpdater.markFailed(id)가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_Fail_callsMarkFailed() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Down"));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markFailed(eq(1L), any());
        then(outboxStatusUpdater).should(never()).markSent(anyCollection(), any());
    }

    @Test
    @DisplayName("payment-succeeded-event: 올바른 topic으로 Kafka 전송 후 markSent가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_paymentSucceeded_routesToCorrectTopic() throws Exception {
        OutboxEvent paymentSucceeded = OutboxEvent.pending("Payment", AGGREGATE_ID, "payment-succeeded-event", "{}");
        ReflectionTestUtils.setField(paymentSucceeded, "id", 2L);

        given(outboxEventClaimer.claimBatch(any(), anyInt()))
                .willReturn(List.of(paymentSucceeded));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("payment-succeeded-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(times(1)).send("payment-succeeded-event", AGGREGATE_ID, "{}");
        then(outboxStatusUpdater).should(times(1)).markSent(eq(List.of(2L)), any());
        then(outboxStatusUpdater).should(never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("payment-failed-event: 올바른 topic으로 Kafka 전송 후 markSent가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_paymentFailed_routesToCorrectTopic() throws Exception {
        OutboxEvent paymentFailed = OutboxEvent.pending("Payment", AGGREGATE_ID, "payment-failed-event", "{}");
        ReflectionTestUtils.setField(paymentFailed, "id", 3L);

        given(outboxEventClaimer.claimBatch(any(), anyInt()))
                .willReturn(List.of(paymentFailed));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("payment-failed-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(times(1)).send("payment-failed-event", AGGREGATE_ID, "{}");
        then(outboxStatusUpdater).should(times(1)).markSent(eq(List.of(3L)), any());
        then(outboxStatusUpdater).should(never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("미등록 eventType: Kafka 전송 없이 markFailed가 호출된다")
    void publish_unknownEventType_callsMarkFailedWithoutKafkaSend() {
        OutboxEvent unknown = OutboxEvent.pending("Orders", AGGREGATE_ID, "unknown-event", "{}");
        ReflectionTestUtils.setField(unknown, "id", 4L);

        given(outboxEventClaimer.claimBatch(any(), anyInt()))
                .willReturn(List.of(unknown));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        then(outboxStatusUpdater).should(times(1)).markFailed(eq(4L), any());
        then(outboxStatusUpdater).should(never()).markSent(anyCollection(), any());
    }

    @Test
    @DisplayName("Kafka 전송 성공 후 SENT 일괄 기록이 실패해도 markFailed()는 호출되지 않는다")
    @SuppressWarnings("unchecked")
    void publish_markSentFailsAfterKafkaSuccess_doesNotMarkFailed() throws Exception {
        OutboxEvent second = OutboxEvent.pending("Orders", "200", "order-create-event", "{}");
        ReflectionTestUtils.setField(second, "id", 5L);
        given(outboxEventClaimer.claimBatch(any(), anyInt())).willReturn(List.of(event, second));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("order-create-event"), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));
        Mockito.doThrow(new RuntimeException("DB down")).when(outboxStatusUpdater).markSent(eq(List.of(1L, 5L)), any());

        outboxPublisherJob.publish();

        // 이미 발행된 이벤트를 재시도 대상으로 되돌리면 중복 발행된다 — PROCESSING은 stale 복구가 되돌린다
        then(outboxStatusUpdater).should(never()).markFailed(anyLong(), any());
        then(kafkaTemplate).should(times(1)).send("order-create-event", "200", "{}");
    }

    @Test
    @DisplayName("배치 중 성공한 행만 모아 한 번에 SENT 처리하고, 실패한 행은 건별로 재시도를 예약한다")
    @SuppressWarnings("unchecked")
    void publish_mixedBatch_marksSentOnceAndFailedPerEvent() throws Exception {
        OutboxEvent failing = OutboxEvent.pending("Orders", "200", "order-create-event", "{}");
        ReflectionTestUtils.setField(failing, "id", 5L);
        OutboxEvent third = OutboxEvent.pending("Orders", "300", "order-create-event", "{}");
        ReflectionTestUtils.setField(third, "id", 6L);
        given(outboxEventClaimer.claimBatch(any(), anyInt())).willReturn(List.of(event, failing, third));

        CompletableFuture<SendResult<String, String>> ok = Mockito.mock(CompletableFuture.class);
        CompletableFuture<SendResult<String, String>> fail = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("order-create-event"), anyString(), anyString())).willReturn(ok);
        given(kafkaTemplate.send("order-create-event", "200", "{}")).willReturn(fail);
        given(ok.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));
        given(fail.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Down"));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markSent(eq(List.of(1L, 6L)), any());
        then(outboxStatusUpdater).should(times(1)).markFailed(eq(5L), any());
        then(outboxStatusUpdater).should(never()).markFailed(eq(1L), any());
        then(outboxStatusUpdater).should(never()).markFailed(eq(6L), any());
    }

    @Test
    @DisplayName("매 실행마다 claim 전에 stale PROCESSING 복구를 설정한 timeout 기준으로 호출한다")
    void publish_recoversStaleProcessingBeforeClaim() {
        given(outboxEventClaimer.claimBatch(any(), anyInt())).willReturn(List.of());
        java.time.ZonedDateTime before = java.time.ZonedDateTime.now();

        outboxPublisherJob.publish();

        org.mockito.InOrder inOrder = Mockito.inOrder(outboxEventClaimer);
        org.mockito.ArgumentCaptor<java.time.ZonedDateTime> threshold =
                org.mockito.ArgumentCaptor.forClass(java.time.ZonedDateTime.class);
        inOrder.verify(outboxEventClaimer).recoverStaleProcessing(threshold.capture(), eq(50));
        inOrder.verify(outboxEventClaimer).claimBatch(any(), eq(50));
        org.assertj.core.api.Assertions.assertThat(threshold.getValue())
                .isBetween(before.minusMinutes(5).minusSeconds(1), java.time.ZonedDateTime.now().minusMinutes(5));
    }
}
