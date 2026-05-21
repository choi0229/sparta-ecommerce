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
import org.teamsparta.orderapi.domain.order.repository.OutboxQueryRepository;
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
    private OutboxQueryRepository outboxQueryRepository;
    @Mock
    private OutboxStatusUpdater outboxStatusUpdater;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEvent event;
    private final String AGGREGATE_ID = "100";

    @BeforeEach
    void setUp() {
        outboxPublisherJob = new OutboxPublisherJob(
                outboxQueryRepository, outboxStatusUpdater, kafkaTemplate, new SimpleMeterRegistry());
        event = OutboxEvent.pending("Orders", AGGREGATE_ID, "order-create-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "retryCount", 0);
        ReflectionTestUtils.setField(event, "status", OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("성공: Kafka 전송 성공 시 OutboxStatusUpdater.markSent(id)가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_Success_callsMarkSent() throws Exception {
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("order-create-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markSent(1L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
        then(kafkaTemplate).should(times(1)).send("order-create-event", AGGREGATE_ID, "{}");
    }

    @Test
    @DisplayName("실패: Kafka 전송 실패 시 OutboxStatusUpdater.markFailed(id)가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_Fail_callsMarkFailed() throws Exception {
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Down"));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());
    }

    @Test
    @DisplayName("payment-succeeded-event: 올바른 topic으로 Kafka 전송 후 markSent가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_paymentSucceeded_routesToCorrectTopic() throws Exception {
        OutboxEvent paymentSucceeded = OutboxEvent.pending("Payment", AGGREGATE_ID, "payment-succeeded-event", "{}");
        ReflectionTestUtils.setField(paymentSucceeded, "id", 2L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(paymentSucceeded));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("payment-succeeded-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(times(1)).send("payment-succeeded-event", AGGREGATE_ID, "{}");
        then(outboxStatusUpdater).should(times(1)).markSent(2L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("payment-failed-event: 올바른 topic으로 Kafka 전송 후 markSent가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_paymentFailed_routesToCorrectTopic() throws Exception {
        OutboxEvent paymentFailed = OutboxEvent.pending("Payment", AGGREGATE_ID, "payment-failed-event", "{}");
        ReflectionTestUtils.setField(paymentFailed, "id", 3L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(paymentFailed));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("payment-failed-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(times(1)).send("payment-failed-event", AGGREGATE_ID, "{}");
        then(outboxStatusUpdater).should(times(1)).markSent(3L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("미등록 eventType: Kafka 전송 없이 markFailed가 호출된다")
    void publish_unknownEventType_callsMarkFailedWithoutKafkaSend() {
        OutboxEvent unknown = OutboxEvent.pending("Orders", AGGREGATE_ID, "unknown-event", "{}");
        ReflectionTestUtils.setField(unknown, "id", 4L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(unknown));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        then(outboxStatusUpdater).should(times(1)).markFailed(4L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());
    }
}
