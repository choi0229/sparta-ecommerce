package org.teamsparta.orderapi.domain.order.dto.response;

import org.teamsparta.orderapi.global.enums.Status;

public record CreateOrderResponse(Long orderId, String orderNo, Status status) {
}
