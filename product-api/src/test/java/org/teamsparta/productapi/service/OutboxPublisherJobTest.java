package org.teamsparta.productapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxQueryRepository;
import org.teamsparta.productapi.domain.product.scheduler.OutboxPublisherJob;
import org.teamsparta.productapi.domain.product.scheduler.OutboxStatusUpdater;
import org.teamsparta.productapi.global.enums.OutboxStatus;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OutboxPublisherJobTest {

    @InjectMocks
    private OutboxPublisherJob outboxPublisherJob;

    @Mock
    private OutboxQueryRepository outboxQueryRepository;
    @Mock
    private OutboxStatusUpdater outboxStatusUpdater;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private static final String AGGREGATE_ID = "SKU-1";

    private OutboxEvent makeEvent(String eventType) {
        OutboxEvent event = OutboxEvent.pending("Product", AGGREGATE_ID, eventType, "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    @Test
    @DisplayName("Kafka 전송 성공 → outboxStatusUpdater.markSent() 호출, markFailed() 미호출")
    void publish_kafkaSuccess_callsMarkSent() throws Exception {
        // given
        OutboxEvent event = makeEvent("product-variant-event");
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq(AGGREGATE_ID), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        // when
        outboxPublisherJob.publish();

        // then
        then(outboxStatusUpdater).should(times(1)).markSent(1L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("Kafka 전송 실패 → outboxStatusUpdater.markFailed() 호출, markSent() 미호출")
    void publish_kafkaFail_callsMarkFailed() throws Exception {
        // given
        OutboxEvent event = makeEvent("product-variant-event");
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq(AGGREGATE_ID), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new TimeoutException("Kafka timeout"));

        // when
        outboxPublisherJob.publish();

        // then
        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());
    }

    @Test
    @DisplayName("Kafka 전송 성공 후 markSent() 예외 발생 → markFailed() 호출 금지 (중복 발행 방지)")
    void publish_markSentFailsAfterKafkaSuccess_doesNotCallMarkFailed() throws Exception {
        // given
        OutboxEvent event = makeEvent("product-variant-event");
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq(AGGREGATE_ID), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        doThrow(new RuntimeException("DB connection lost")).when(outboxStatusUpdater).markSent(1L);

        // when
        outboxPublisherJob.publish();

        // then: Kafka 성공 후 markSent 실패 → markFailed 호출 없어야 함
        then(outboxStatusUpdater).should(times(1)).markSent(1L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("미등록 eventType → markFailed() 호출, Kafka 전송 없음")
    void publish_unknownEventType_callsMarkFailed() {
        // given
        OutboxEvent event = makeEvent("unknown-event-type");
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        then(kafkaTemplate).should(never()).send(anyString(), anyString(), anyString());
        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());
    }

    @Test
    @DisplayName("InterruptedException → interrupt flag 복원 후 markFailed, 루프 중단")
    void publish_interruptedException_restoresInterruptFlagAndCallsMarkFailed() throws Exception {
        // given
        OutboxEvent event1 = makeEvent("product-variant-event");
        OutboxEvent event2 = OutboxEvent.pending("Product", "SKU-2", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event2, "id", 2L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event1, event2));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq(AGGREGATE_ID), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new InterruptedException("interrupted"));

        // when
        outboxPublisherJob.publish();

        // then: interrupt flag 복원 확인
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // event1만 markFailed, event2는 루프 중단으로 처리 안 됨
        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markFailed(2L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());

        // interrupt flag 정리 (다음 테스트 영향 방지)
        Thread.interrupted();
    }

    @Test
    @DisplayName("Kafka 실패 후 markFailed() 예외 발생 → 루프 계속 진행 (다음 이벤트 처리)")
    void publish_kafkaFail_markFailedThrows_loopContinuesToNextEvent() throws Exception {
        // given
        OutboxEvent event1 = makeEvent("product-variant-event");
        OutboxEvent event2 = OutboxEvent.pending("Product", "SKU-2", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event2, "id", 2L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event1, event2));

        CompletableFuture<SendResult<String, String>> future1 = Mockito.mock(CompletableFuture.class);
        CompletableFuture<SendResult<String, String>> future2 = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq(AGGREGATE_ID), eq("{}"))).willReturn(future1);
        given(kafkaTemplate.send(eq("product-variant-event"), eq("SKU-2"), eq("{}"))).willReturn(future2);
        given(future1.get(2, TimeUnit.SECONDS)).willThrow(new TimeoutException("Kafka timeout"));
        given(future2.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        // event1의 markFailed는 예외를 던지지만 루프가 중단되어서는 안 됨
        doThrow(new RuntimeException("DB error")).when(outboxStatusUpdater).markFailed(1L);

        // when
        outboxPublisherJob.publish();

        // then: event1 markFailed 호출 후 예외 발생해도 event2는 정상 처리
        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(times(1)).markSent(2L);
        then(outboxStatusUpdater).should(never()).markFailed(2L);
    }

    @Test
    @DisplayName("InterruptedException 발생 시 markFailed() 예외 발생 → interrupt flag 복원 후 루프 중단")
    void publish_interruptedException_markFailedThrows_interruptFlagStillRestoredAndBreaks() throws Exception {
        // given
        OutboxEvent event1 = makeEvent("product-variant-event");
        OutboxEvent event2 = OutboxEvent.pending("Product", "SKU-2", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event2, "id", 2L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event1, event2));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("product-variant-event"), eq(AGGREGATE_ID), eq("{}"))).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new InterruptedException("interrupted"));

        // markFailed도 예외를 던지는 최악의 상황
        doThrow(new RuntimeException("DB error")).when(outboxStatusUpdater).markFailed(1L);

        // when
        outboxPublisherJob.publish();

        // then: markFailed 예외에도 불구하고 interrupt flag는 반드시 복원되어야 함
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // event2는 루프 중단으로 처리 안 됨
        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markFailed(2L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());

        // interrupt flag 정리 (다음 테스트 영향 방지)
        Thread.interrupted();
    }
}
