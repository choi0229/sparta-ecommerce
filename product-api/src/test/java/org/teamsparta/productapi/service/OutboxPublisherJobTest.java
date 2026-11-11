package org.teamsparta.productapi.service;

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
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.scheduler.OutboxEventClaimer;
import org.teamsparta.productapi.domain.product.scheduler.OutboxPublisherJob;
import org.teamsparta.productapi.domain.product.scheduler.OutboxStatusUpdater;
import org.teamsparta.productapi.global.enums.OutboxStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    @BeforeEach
    void setUp() {
        outboxPublisherJob = new OutboxPublisherJob(
                outboxEventClaimer, outboxStatusUpdater, kafkaTemplate, new SimpleMeterRegistry(), 50, 300_000L);
    }

    private OutboxEvent processing(String eventType) {
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", eventType, "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "status", OutboxStatus.PROCESSING);
        return event;
    }

    @Test
    @DisplayName("성공: claim한 이벤트를 Kafka로 전송하고 markSent(id)를 호출한다")
    @SuppressWarnings("unchecked")
    void publish_success() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), eq(50))).willReturn(List.of(processing("variant-created-event")));
        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("variant-created-event"), eq("SKU-1"), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(null);

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(times(1)).send("variant-created-event", "SKU-1", "{}");
        then(outboxStatusUpdater).should(times(1)).markSent(1L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("실패: Kafka 전송 timeout이면 markFailed(id)로 재시도를 예약한다")
    @SuppressWarnings("unchecked")
    void publish_fail_retry() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), eq(50))).willReturn(List.of(processing("product-variant-event")));
        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq("SKU-1"), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new TimeoutException("Kafka timeout"));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());
    }

    @Test
    @DisplayName("실패: Kafka 전송 예외(ExecutionException)도 markFailed(id)로 처리한다")
    @SuppressWarnings("unchecked")
    void publish_fail_executionException() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), eq(50))).willReturn(List.of(processing("product-variant-event")));
        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq("SKU-1"), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new ExecutionException(new RuntimeException("Kafka timeout")));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
    }

    @Test
    @DisplayName("실패: 알 수 없는 eventType이면 Kafka 전송 없이 markFailed(id)를 호출한다")
    void publish_fail_unknown_eventType() {
        given(outboxEventClaimer.claimBatch(any(), eq(50))).willReturn(List.of(processing("unknown-event-type")));

        outboxPublisherJob.publish();

        then(kafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
    }

    @Test
    @DisplayName("Kafka 전송 성공 후 markSent()가 실패해도 markFailed()는 호출되지 않는다")
    @SuppressWarnings("unchecked")
    void publish_markSentFailsAfterKafkaSuccess_doesNotMarkFailed() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), eq(50))).willReturn(List.of(processing("variant-created-event")));
        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(null);
        Mockito.doThrow(new RuntimeException("DB down")).when(outboxStatusUpdater).markSent(1L);

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
    }
}
