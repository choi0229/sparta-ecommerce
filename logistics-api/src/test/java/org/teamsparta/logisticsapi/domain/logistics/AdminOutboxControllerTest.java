package org.teamsparta.logisticsapi.domain.logistics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.logisticsapi.domain.logistics.controller.AdminOutboxController;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class AdminOutboxControllerTest {

    @Mock OutboxEventTransactionalService service;
    @Spy MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private AdminOutboxController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminOutboxController(service, meterRegistry);
    }

    private OutboxEvent failedEvent(Long id, String eventType, String aggregateId, int retryCount) {
        OutboxEvent e = OutboxEvent.pending("Shipment", aggregateId, eventType, "{}");
        ReflectionTestUtils.setField(e, "id", id);
        ReflectionTestUtils.setField(e, "status", OutboxStatus.PENDING); // resetForRetry 후 상태
        ReflectionTestUtils.setField(e, "retryCount", retryCount);
        return e;
    }

    @Test
    @DisplayName("단건 재처리 성공 시 eventType·aggregateId를 포함한 응답과 single 카운터가 증가한다")
    void retryFailed_success_returnsEnrichedResponseAndIncrementsCounter() {
        OutboxEvent event = failedEvent(1L, "shipment-created-event", "SHIP-001", 5);
        given(service.retryFailed(eq(1L), any())).willReturn(event);

        var resp = controller.retryFailed(1L);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AdminOutboxController.RetryResponse body = resp.getBody().getData();
        assertThat(body.id()).isEqualTo(1L);
        assertThat(body.eventType()).isEqualTo("shipment-created-event");
        assertThat(body.aggregateId()).isEqualTo("SHIP-001");
        assertThat(body.retryCount()).isEqualTo(5);
        assertThat(meterRegistry.counter("logistics.outbox.admin.retry", "type", "single").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("배치 재처리 성공 시 count·ids·items를 포함한 응답과 배치 카운터가 이벤트 수만큼 증가한다")
    void retryBatch_success_returnsEnrichedResponseAndIncrementsCounter() {
        OutboxEvent e1 = failedEvent(1L, "shipment-created-event", "SHIP-001", 5);
        OutboxEvent e2 = failedEvent(2L, "shipment-status-changed-event", "SHIP-002", 3);
        given(service.retryFailedBatch(eq(OutboxStatus.FAILED), anyInt(), any()))
                .willReturn(List.of(e1, e2));

        var resp = controller.retryBatch(20);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        AdminOutboxController.BatchRetryResponse body = resp.getBody().getData();
        assertThat(body.count()).isEqualTo(2);
        assertThat(body.ids()).containsExactly(1L, 2L);
        assertThat(body.items()).hasSize(2);
        assertThat(body.items().get(0).eventType()).isEqualTo("shipment-created-event");
        assertThat(body.items().get(1).aggregateId()).isEqualTo("SHIP-002");
        assertThat(meterRegistry.counter("logistics.outbox.admin.retry", "type", "batch").count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("배치 재처리 대상이 없으면 count=0·빈 items를 반환하고 배치 카운터는 증가하지 않는다")
    void retryBatch_noEvents_returnsEmptyAndCounterNotIncremented() {
        given(service.retryFailedBatch(eq(OutboxStatus.FAILED), anyInt(), any()))
                .willReturn(List.of());

        var resp = controller.retryBatch(20);

        assertThat(resp.getBody().getData().count()).isEqualTo(0);
        assertThat(resp.getBody().getData().ids()).isEmpty();
        assertThat(resp.getBody().getData().items()).isEmpty();
        assertThat(meterRegistry.counter("logistics.outbox.admin.retry", "type", "batch").count())
                .isEqualTo(0.0);
    }
}
