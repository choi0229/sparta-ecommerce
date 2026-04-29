package org.teamsparta.orderapi.domain.order.dto.response;

public record OrderStatusResponse(
        String idemKey,
        String status,           // PENDING / CREATED / FAILED 등
        Long orderId,            // 주문 생성 완료 후 값 있음
        String shipmentStatus,   // 배송 이벤트 수신 전까지 null
        String failureReason     // FAILED 시 실패 사유, 그 외 null
) {
}
