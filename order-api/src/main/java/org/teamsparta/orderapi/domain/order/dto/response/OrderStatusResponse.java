package org.teamsparta.orderapi.domain.order.dto.response;

public record OrderStatusResponse(
        String idemKey,
        String status,      // PENDING, COMPLETED, FAILED
        Long orderId        // COMPLETED일 때만 값 있음
) {
}
