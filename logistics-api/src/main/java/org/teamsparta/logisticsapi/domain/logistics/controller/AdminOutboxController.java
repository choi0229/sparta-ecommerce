package org.teamsparta.logisticsapi.domain.logistics.controller;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class AdminOutboxController {

    private final OutboxEventTransactionalService outboxEventTransactionalService;

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

    @PostMapping("/retry")
    public ResponseEntity<ApiResponse<BatchRetryResponse>> retryBatch(
            @RequestParam OutboxStatus status,
            @RequestParam(defaultValue = "20") int limit) {
        List<OutboxEvent> retried = outboxEventTransactionalService
                .retryFailedBatch(status, limit, ZonedDateTime.now());
        List<Long> ids = retried.stream().map(OutboxEvent::getId).toList();
        return ResponseEntity.ok(ApiResponse.ok(new BatchRetryResponse(ids.size(), ids)));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<RetryResponse>> retryFailed(@PathVariable Long id) {
        OutboxEvent event = outboxEventTransactionalService.retryFailed(id, ZonedDateTime.now());
        return ResponseEntity.ok(ApiResponse.ok(
                new RetryResponse(event.getId(), event.getStatus().name(), event.getRetryCount())
        ));
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

    public record BatchRetryResponse(int count, List<Long> ids) {}

    public record RetryResponse(Long id, String status, int retryCount) {}
}
