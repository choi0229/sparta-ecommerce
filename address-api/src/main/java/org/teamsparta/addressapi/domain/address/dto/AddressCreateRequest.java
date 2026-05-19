package org.teamsparta.addressapi.domain.address.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddressCreateRequest(
        @NotNull Long userId,
        @NotBlank String recipientName,
        @NotBlank String recipientAddress,
        boolean isDefault
) {
}
