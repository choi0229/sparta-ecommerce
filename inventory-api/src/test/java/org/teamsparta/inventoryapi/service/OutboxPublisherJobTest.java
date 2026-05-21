package org.teamsparta.inventoryapi.service;

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
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxEventClaimer;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxPublisherJob;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxStatusUpdater;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;

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
    private final String AGGREGATE_ID = "SKU-001";

    @BeforeEach
    void setUp() {
        outboxPublisherJob = new OutboxPublisherJob(
                outboxEventClaimer, outboxStatusUpdater, kafkaTemplate, new SimpleMeterRegistry());
        // claimBatch()가 반환하는 이벤트는 이미 PROCESSING 상태
        event = OutboxEvent.pending("Inventory", AGGREGATE_ID, "inventory-created-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "status", OutboxStatus.PROCESSING);
    }

    @Test
    @DisplayName("배치가 비어 있으면 markSent/markFailed가 호출되지 않는다")
    void publish_emptyBatch_noInteractions() {
        given(outboxEventClaimer.claimBatch(any(), anyInt())).willReturn(List.of());

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).shouldHaveNoInteractions();
        then(kafkaTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Kafka 전송 성공 시 OutboxStatusUpdater.markSent(id)가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_success_callsMarkSent() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), anyInt())).willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(eq("inventory-created-event"), eq(AGGREGATE_ID), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willReturn(Mockito.mock(SendResult.class));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markSent(1L);
        then(outboxStatusUpdater).should(never()).markFailed(anyLong());
        then(kafkaTemplate).should(times(1)).send("inventory-created-event", AGGREGATE_ID, "{}");
    }

    @Test
    @DisplayName("Kafka 전송 실패 시 OutboxStatusUpdater.markFailed(id)가 호출된다")
    @SuppressWarnings("unchecked")
    void publish_fail_callsMarkFailed() throws Exception {
        given(outboxEventClaimer.claimBatch(any(), anyInt())).willReturn(List.of(event));

        CompletableFuture<SendResult<String, String>> future = Mockito.mock(CompletableFuture.class);
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(future);
        given(future.get(2, TimeUnit.SECONDS)).willThrow(new RuntimeException("Kafka Network Error"));

        outboxPublisherJob.publish();

        then(outboxStatusUpdater).should(times(1)).markFailed(1L);
        then(outboxStatusUpdater).should(never()).markSent(anyLong());
    }
}
