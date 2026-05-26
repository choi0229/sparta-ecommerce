package org.teamsparta.logisticsapi.domain.logistics.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ShipmentCancelRequest(
        @NotBlank String cancelReason
) {}
