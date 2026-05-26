package org.teamsparta.logisticsapi.domain.logistics.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentAddressUpdateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCancelRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentCreateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.request.ShipmentStatusUpdateRequest;
import org.teamsparta.logisticsapi.domain.logistics.dto.response.ShipmentResponse;
import org.teamsparta.logisticsapi.domain.logistics.service.LogisticsService;
import org.teamsparta.logisticsapi.global.response.ApiResponse;

@RestController
@RequestMapping("/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final LogisticsService logisticsService;

    @PostMapping
    public ResponseEntity<ApiResponse<ShipmentResponse>> createShipment(
            @Valid @RequestBody ShipmentCreateRequest request) {
        ShipmentResponse response = logisticsService.createShipment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(response));
    }

    @GetMapping("/{shipmentId}")
    public ResponseEntity<ApiResponse<ShipmentResponse>> getShipment(
            @PathVariable Long shipmentId) {
        ShipmentResponse response = logisticsService.getShipment(shipmentId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/by-order/{orderId}")
    public ResponseEntity<ApiResponse<ShipmentResponse>> getShipmentByOrderId(
            @PathVariable Long orderId) {
        ShipmentResponse response = logisticsService.getShipmentByOrderId(orderId);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{shipmentId}/status")
    public ResponseEntity<ApiResponse<ShipmentResponse>> updateStatus(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentStatusUpdateRequest request) {
        ShipmentResponse response = logisticsService.updateStatus(shipmentId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{shipmentId}/cancel")
    public ResponseEntity<ApiResponse<ShipmentResponse>> cancelShipment(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentCancelRequest request) {
        ShipmentResponse response = logisticsService.cancelShipment(shipmentId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PatchMapping("/{shipmentId}/address")
    public ResponseEntity<ApiResponse<ShipmentResponse>> updateAddress(
            @PathVariable Long shipmentId,
            @Valid @RequestBody ShipmentAddressUpdateRequest request) {
        ShipmentResponse response = logisticsService.updateAddress(shipmentId, request);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
