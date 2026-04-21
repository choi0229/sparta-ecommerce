package org.teamsparta.logisticsapi.domain.logistics.dto.response;

import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;

import java.time.ZonedDateTime;

public record ShipmentResponse(
        Long id,
        Long orderId,
        String trackingNumber,
        ShipmentStatus status,
        String carrier,
        String recipientName,
        String recipientAddress,
        ZonedDateTime createdAt,
        ZonedDateTime updatedAt
) {
    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getOrderId(),
                shipment.getTrackingNumber(),
                shipment.getStatus(),
                shipment.getCarrier(),
                shipment.getRecipientName(),
                shipment.getRecipientAddress(),
                shipment.getCreatedAt(),
                shipment.getUpdatedAt()
        );
    }
}
