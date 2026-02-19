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
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.OutboxQueryRepository;
import org.teamsparta.productapi.domain.product.scheduler.OutboxPublisherJob;
import org.teamsparta.productapi.global.enums.OutboxStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class OutboxPublisherJobTest {

    @InjectMocks
    private OutboxPublisherJob outboxPublisherJob;

    @Mock
    private OutboxQueryRepository outboxQueryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("성공: 보류 중인 이벤트를 Kafka로 전송하고 완료 상태로 변경한다")
    void publish_success()throws Exception{
        // given
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "variant-created-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);

        given(outboxQueryRepository.findBatchForPublish(any(), eq(50)))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);

        given(kafkaTemplate.send(eq("variant-created-event"), eq("SKU-1"), eq("{}")))
                .willReturn(future);

        given(future.get(2, TimeUnit.SECONDS)).willReturn(null);

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(outboxEventRepository, times(1)).findById(1L);
        verify(kafkaTemplate, times(1)).send(eq("variant-created-event"), eq("SKU-1"), eq("{}"));
    }

    @Test
    @DisplayName("실패: Kafka 전송 에러 발생 시 재시도 횟수가 증가해야 한다")
    void publish_fail_retry() throws Exception {
        // given
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);

        given(outboxQueryRepository.findBatchForPublish(any(), eq(50)))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);

        given(kafkaTemplate.send(eq("product-variant-event"), eq("SKU-1"), eq("{}")))
                .willReturn(future);

        given(future.get(2, TimeUnit.SECONDS)).willThrow(new TimeoutException("Kafka timeout"));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("실패: kafka 재시도 횟수 최대치 도달시 fail로 변경")
    void publish_fail_max_retry() throws Exception {
        // given
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);

        for(int i = 0; i < 4; i++){
            event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        }

        given(outboxQueryRepository.findBatchForPublish(any(), eq(50)))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future =
                Mockito.mock(CompletableFuture.class);

        given(kafkaTemplate.send(eq("product-variant-event"), eq("SKU-1"), eq("{}")))
                .willReturn(future);

        given(future.get(2, TimeUnit.SECONDS)).willThrow(new ExecutionException(new RuntimeException("Kafka timeout")));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @DisplayName("실패: 알 수없는 eventType 일경우 실패처리")
    void publish_fail_unknown_eventType()throws Exception{
        // given
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "unknown-event-type", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);

        given(outboxQueryRepository.findBatchForPublish(any(), eq(50)))
                .willReturn(List.of(event));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        verify(kafkaTemplate, times(0)).send(anyString(), anyString(), anyString());

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);

        verify(outboxEventRepository, times(1)).findById(1L);
    }
}
