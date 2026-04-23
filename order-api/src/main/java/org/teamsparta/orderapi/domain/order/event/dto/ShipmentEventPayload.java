package org.teamsparta.orderapi.domain.order.event.dto;

public record ShipmentEventPayload(
        String eventId,
        Long shipmentId,
        Long orderId,
        String status,
        String description
) {}
