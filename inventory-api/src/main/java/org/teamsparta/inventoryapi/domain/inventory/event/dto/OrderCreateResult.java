package org.teamsparta.inventoryapi.domain.inventory.event.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

// order-create-event에는 다른 Consumer용 필드(배송지 등)도 포함되므로, 예약에 필요한 필드만 읽는다
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCreateResult(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId,
        Long userId,
        List<Item> items
) {
    public record Item(
            String sku,
            Integer quantity
    ) {}
}
