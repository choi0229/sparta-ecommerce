package org.teamsparta.addressapi.domain.address.dto;

import jakarta.validation.constraints.AssertTrue;

public record AddressPatchRequest(
        String recipientName,
        String recipientAddress,
        Boolean isDefault
) {
    // null → 미수정 허용, non-null blank → 400
    @AssertTrue(message = "recipientName이 비어 있을 수 없습니다")
    public boolean isRecipientNameValid() {
        return recipientName == null || !recipientName.isBlank();
    }

    @AssertTrue(message = "recipientAddress가 비어 있을 수 없습니다")
    public boolean isRecipientAddressValid() {
        return recipientAddress == null || !recipientAddress.isBlank();
    }
}
