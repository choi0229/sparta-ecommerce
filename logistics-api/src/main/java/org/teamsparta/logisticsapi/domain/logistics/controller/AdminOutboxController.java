package org.teamsparta.logisticsapi.domain.logistics.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.response.ApiResponse;

import java.time.ZonedDateTime;

@RestController
@RequestMapping("/admin/outbox")
@RequiredArgsConstructor
public class AdminOutboxController {

    private final OutboxEventTransactionalService outboxEventTransactionalService;

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<RetryResponse>> retryFailed(@PathVariable Long id) {
        OutboxEvent event = outboxEventTransactionalService.retryFailed(id, ZonedDateTime.now());
        return ResponseEntity.ok(ApiResponse.ok(
                new RetryResponse(event.getId(), event.getStatus().name(), event.getRetryCount())
        ));
    }

    public record RetryResponse(Long id, String status, int retryCount) {}
}
