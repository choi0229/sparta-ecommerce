package org.teamsparta.productapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.repository.OutboxQueryRepository;
import org.teamsparta.productapi.domain.product.scheduler.OutboxPublisherJob;
import org.teamsparta.productapi.global.enums.OutboxStatus;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture future = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        verify(kafkaTemplate, times(1)).send(eq("variant-created-event"), anyString(), anyString());
    }

    @Test
    @DisplayName("실패: Kafka 전송 에러 발생 시 재시도 횟수가 증가해야 한다")
    void publish_Failure_Retry() throws Exception {
        // given
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);

        given(outboxQueryRepository.findBatchForPublish(any(), anyInt()))
                .willReturn(List.of(event));

        given(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .willThrow(new RuntimeException("Kafka Down"));

        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // when
        outboxPublisherJob.publish();

        // then
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }
}
