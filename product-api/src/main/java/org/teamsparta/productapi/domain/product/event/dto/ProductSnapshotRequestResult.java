package org.teamsparta.productapi.domain.product.event.dto;

import java.util.List;
import java.util.UUID;

public record ProductSnapshotRequestResult(
        UUID requestId,
        String eventType,
        List<String> skus
) {
}
