package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.scheduler.OutboxPublisherJob;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherJobTest {

    @Mock OutboxEventTransactionalService outboxEventTransactionalService;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @InjectMocks OutboxPublisherJob job;

    private OutboxEvent pendingShipmentEvent() {
        OutboxEvent event = OutboxEvent.pending("shipment", "1", "shipment-created-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    @Test
    @DisplayName("배치가 비어 있으면 kafkaTemplate.send, markSent, markFailed가 호출되지 않는다")
    void emptyBatch_noInteractions() {
        given(outboxEventTransactionalService.fetchBatch(any(), anyInt())).willReturn(List.of());

        job.publish();

        then(outboxEventTransactionalService).should().fetchBatch(any(), eq(50));
        then(outboxEventTransactionalService).should(never()).markSent(anyLong());
        then(outboxEventTransactionalService).should(never()).markFailed(anyLong());
        then(kafkaTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Kafka send 성공 시 kafkaTemplate.send가 호출되고 markSent(eventId)가 호출된다")
    @SuppressWarnings("unchecked")
    void kafkaSendSuccess_callsMarkSent() {
        OutboxEvent event = pendingShipmentEvent();
        given(outboxEventTransactionalService.fetchBatch(any(), anyInt())).willReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> successFuture =
                CompletableFuture.completedFuture(mock(SendResult.class));
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(successFuture);

        job.publish();

        then(kafkaTemplate).should(times(1)).send("shipment-event", "1", "{}");
        then(outboxEventTransactionalService).should(times(1)).markSent(1L);
        then(outboxEventTransactionalService).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("Kafka send 실패 시 markFailed(eventId)가 호출되고 markSent는 호출되지 않는다")
    void kafkaSendFails_callsMarkFailed() {
        OutboxEvent event = pendingShipmentEvent();
        given(outboxEventTransactionalService.fetchBatch(any(), anyInt())).willReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("kafka send timeout"));
        given(kafkaTemplate.send(anyString(), anyString(), anyString())).willReturn(failedFuture);

        job.publish();

        then(outboxEventTransactionalService).should(times(1)).markFailed(1L);
        then(outboxEventTransactionalService).should(never()).markSent(anyLong());
    }

    @Test
    @DisplayName("미등록 eventType이면 kafkaTemplate.send는 호출되지 않고 markFailed(eventId)가 호출된다")
    void unknownEventType_callsMarkFailedWithoutKafkaSend() {
        OutboxEvent event = OutboxEvent.pending("shipment", "1", "unknown-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        given(outboxEventTransactionalService.fetchBatch(any(), anyInt())).willReturn(List.of(event));

        job.publish();

        then(kafkaTemplate).shouldHaveNoInteractions();
        then(outboxEventTransactionalService).should(times(1)).markFailed(1L);
        then(outboxEventTransactionalService).should(never()).markSent(anyLong());
    }
}
