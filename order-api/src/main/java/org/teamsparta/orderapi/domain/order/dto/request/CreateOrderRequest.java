package org.teamsparta.orderapi.domain.order.dto.request;

import lombok.Getter;

import java.util.List;

public record CreateOrderRequest(Long userId, List<Item> items) {

    public record Item(
            String sku,
            Integer quantity
    ) {}
}
