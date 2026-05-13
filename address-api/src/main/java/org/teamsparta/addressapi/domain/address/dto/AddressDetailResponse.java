package org.teamsparta.addressapi.domain.address.dto;

import org.teamsparta.addressapi.domain.address.entity.UserAddress;

public record AddressDetailResponse(
        Long id,
        Long userId,
        String recipientName,
        String recipientAddress,
        boolean isDefault
) {
    public static AddressDetailResponse from(UserAddress address) {
        return new AddressDetailResponse(
                address.getId(),
                address.getUserId(),
                address.getRecipientName(),
                address.getRecipientAddress(),
                address.isDefault()
        );
    }
}
