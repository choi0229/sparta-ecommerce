package org.teamsparta.addressapi.domain.address.dto;

import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;

import java.time.LocalDateTime;

public record AddressHistoryResponse(
        Long id,
        Long addressId,
        Long userId,
        String actionType,
        String beforeRecipientName,
        String beforeRecipientAddress,
        Boolean beforeIsDefault,
        String afterRecipientName,
        String afterRecipientAddress,
        Boolean afterIsDefault,
        LocalDateTime changedAt
) {
    public static AddressHistoryResponse from(UserAddressHistory h) {
        return new AddressHistoryResponse(
                h.getId(),
                h.getAddressId(),
                h.getUserId(),
                h.getActionType().name(),
                h.getBeforeRecipientName(),
                h.getBeforeRecipientAddress(),
                h.getBeforeIsDefault(),
                h.getAfterRecipientName(),
                h.getAfterRecipientAddress(),
                h.getAfterIsDefault(),
                h.getCreatedAt()
        );
    }
}
