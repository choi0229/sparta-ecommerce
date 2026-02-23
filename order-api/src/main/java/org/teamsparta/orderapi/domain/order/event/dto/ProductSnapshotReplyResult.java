package org.teamsparta.orderapi.domain.order.event.dto;

import org.teamsparta.orderapi.global.enums.Status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProductSnapshotReplyResult(
        UUID requestId,
        String eventType,
        boolean success,
        String error,
        List<ProductSnapshotItem> items
) {
    public record ProductSnapshotItem(
            String sku,
            Long variantId,
            String productName,
            Long productId,
            BigDecimal price,
            Map<String, Object> optionJson
    ){}
}


