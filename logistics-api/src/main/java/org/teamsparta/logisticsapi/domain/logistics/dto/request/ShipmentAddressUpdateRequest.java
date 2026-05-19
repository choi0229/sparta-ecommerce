package org.teamsparta.logisticsapi.domain.logistics.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ShipmentAddressUpdateRequest(
        @NotBlank String recipientName,
        @NotBlank String recipientAddress
) {}
