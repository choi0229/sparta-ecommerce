package org.teamsparta.logisticsapi.domain.logistics.dto.request;

import jakarta.validation.constraints.NotNull;

public record ShipmentCreateRequest(
        @NotNull Long orderId,
        String recipientName,
        String recipientAddress
) {}
