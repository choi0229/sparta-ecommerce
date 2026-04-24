package org.teamsparta.orderapi.domain.order.dto.response;

public record OrderStatusResponse(
        String idemKey,
        String status,           // PENDING, COMPLETED, FAILED (IdempotencyStatus)
        Long orderId,            // COMPLETED일 때만 값 있음
        String shipmentStatus    // Orders.shipmentStatus — 배송 이벤트 수신 전까지 null
) {
}
