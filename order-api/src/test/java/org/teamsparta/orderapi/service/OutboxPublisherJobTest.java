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
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.order.repository.OutboxQueryRepository;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxPublisherJob;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class OutboxPublisherJobTest {

    private OutboxPublisherJob outboxPublisherJob;

    @Mock
    private OutboxQueryRepository outboxQueryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEvent event;
    private final String AGGREGATE_ID = "100";

    @BeforeEach
    void setUp() {
        outboxPublisherJob = new OutboxPublisherJob(
                outboxQueryRepository, outboxEventRepository, kafkaTemplate, new SimpleMeterRegistry());
        event = OutboxEvent.pending("Orders", AGGREGATE_ID, "order-create-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "retryCount", 0);
        ReflectionTestUtils.setField(event, "status", OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("성공: 이벤트를 Kafka로 전송하고 SENT 상태로 변경")
    void publish_Success() throws Exception {
        // given
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("order-create-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        then(kafkaTemplate).should(times(1)).send("order-create-event", AGGREGATE_ID, "{}");
        then(outboxEventRepository).should(times(1)).save(any());
    }

    @Test
    @DisplayName("실패: Kafka 전송 에러 발생 시 재시도 횟수가 증가하고 PENDING을 유지한다")
    void publish_Fail_Retry() throws Exception {
        // given
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Down"));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("실패: 재시도 5회 초과 시 FAILED 상태로 변경")
    void publish_Fail_MaxRetry() throws Exception {
        // given
        // 이미 4번 실패한 상태로 설정
        for(int i = 0; i < 4; i++) {
            event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        }

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Down"));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
