package org.teamsparta.inventoryapi.service;

import org.junit.jupiter.api.BeforeEach;
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
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxQueryRepository;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxPublisherJob;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;

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

    @InjectMocks
    private OutboxPublisherJob outboxPublisherJob;

    @Mock
    private OutboxQueryRepository outboxQueryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEvent event;
    private final String AGGREGATE_ID = "SKU-001";

    @BeforeEach
    void setUp() {
        event = OutboxEvent.pending("Inventory", AGGREGATE_ID, "inventory-created-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "retryCount", 0);
        ReflectionTestUtils.setField(event, "status", OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("성공: 재고 생성 이벤트를 Kafka로 전송하고 SENT 상태로 변경")
    void publish_Success() throws Exception {
        // given
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("inventory-created-event"), eq(AGGREGATE_ID), anyString()))
                .willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        then(kafkaTemplate).should(times(1)).send("inventory-created-event", AGGREGATE_ID, "{}");
        then(outboxEventRepository).should(times(1)).save(any());
    }

    @Test
    @DisplayName("실패: 전송 에러 발생 시 재시도 횟수 증가 및 PENDING 유지")
    void publish_Fail_Retry() throws Exception {
        // given
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Network Error"));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("실패: 재시도 5회 도달 시 FAILED 상태로 전이")
    void publish_Fail_MaxRetry() throws Exception {
        // given
        // 4번 실패한 상태로 세팅
        for(int i = 0; i < 4; i++) {
            event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        }

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Fatal Error"));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
