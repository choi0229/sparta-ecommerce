package org.teamsparta.logisticsapi.domain.logistics.controller;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;
import org.teamsparta.logisticsapi.global.response.ApiResponse;

import java.time.ZonedDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/outbox")
@Slf4j
public class AdminOutboxController {

    private final OutboxEventTransactionalService outboxEventTransactionalService;
    private final Counter adminRetrySingleCounter;
    private final Counter adminRetryBatchCounter;

    public AdminOutboxController(OutboxEventTransactionalService outboxEventTransactionalService,
                                 MeterRegistry meterRegistry) {
        this.outboxEventTransactionalService = outboxEventTransactionalService;
        this.adminRetrySingleCounter = Counter.builder("logistics.outbox.admin.retry")
                .tag("type", "single")
                .description("admin-triggered single outbox event retry count")
                .register(meterRegistry);
        this.adminRetryBatchCounter = Counter.builder("logistics.outbox.admin.retry")
                .tag("type", "batch")
                .description("admin-triggered batch outbox event retry count (events, not calls)")
                .register(meterRegistry);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<OutboxEventResponse>>> listByStatus(
            @RequestParam OutboxStatus status,
            @RequestParam(defaultValue = "20") int limit) {
        List<OutboxEventResponse> result = outboxEventTransactionalService
                .findByStatus(status, limit)
                .stream()
                .map(OutboxEventResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    // FAILED 이벤트만 재처리 가능하다. status 파라미터는 제거해 계약을 명확히 한다.
    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<BatchRetryResponse>> retryBatch(
            @RequestParam(defaultValue = "20") int limit) {
        List<OutboxEvent> retried = outboxEventTransactionalService
                .retryFailedBatch(OutboxStatus.FAILED, limit, ZonedDateTime.now());
        List<Long> ids = retried.stream().map(OutboxEvent::getId).toList();
        List<BatchRetryItem> items = retried.stream().map(BatchRetryItem::from).toList();

        log.info("[AdminRetry] batch: count={} ids={}", ids.size(), ids);
        if (!retried.isEmpty()) {
            adminRetryBatchCounter.increment(retried.size());
        }
        return ResponseEntity.ok(ApiResponse.ok(new BatchRetryResponse(ids.size(), ids, items)));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<RetryResponse>> retryFailed(@PathVariable Long id) {
        OutboxEvent event = outboxEventTransactionalService.retryFailed(id, ZonedDateTime.now());
        log.info("[AdminRetry] single: id={} eventType={} aggregateId={} retryCount={}",
                event.getId(), event.getEventType(), event.getAggregateId(), event.getRetryCount());
        adminRetrySingleCounter.increment();
        return ResponseEntity.ok(ApiResponse.ok(RetryResponse.from(event)));
    }

    public record OutboxEventResponse(
            Long id,
            String aggregateId,
            String eventType,
            String status,
            int retryCount,
            ZonedDateTime createdAt,
            ZonedDateTime nextRetryAt
    ) {
        static OutboxEventResponse from(OutboxEvent e) {
            return new OutboxEventResponse(
                    e.getId(), e.getAggregateId(), e.getEventType(),
                    e.getStatus().name(), e.getRetryCount(),
                    e.getCreatedAt(), e.getNextRetryAt()
            );
        }
    }

    public record RetryResponse(Long id, String eventType, String aggregateId, String status, int retryCount) {
        static RetryResponse from(OutboxEvent e) {
            return new RetryResponse(
                    e.getId(), e.getEventType(), e.getAggregateId(),
                    e.getStatus().name(), e.getRetryCount()
            );
        }
    }

    public record BatchRetryItem(Long id, String eventType, String aggregateId, int retryCount) {
        static BatchRetryItem from(OutboxEvent e) {
            return new BatchRetryItem(e.getId(), e.getEventType(), e.getAggregateId(), e.getRetryCount());
        }
    }

    public record BatchRetryResponse(int count, List<Long> ids, List<BatchRetryItem> items) {}
}
