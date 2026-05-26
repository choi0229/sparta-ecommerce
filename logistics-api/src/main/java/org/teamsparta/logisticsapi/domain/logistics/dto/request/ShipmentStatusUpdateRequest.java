package org.teamsparta.logisticsapi.domain.logistics.dto.request;

import jakarta.validation.constraints.NotNull;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;

public record ShipmentStatusUpdateRequest(
        @NotNull ShipmentStatus status,
        String description
) {}
