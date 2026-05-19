package org.teamsparta.addressapi.domain.address.dto;

import org.springframework.data.domain.Page;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;

import java.util.List;

public record AddressHistoryPageResponse(
        List<AddressHistoryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static AddressHistoryPageResponse from(Page<UserAddressHistory> pageResult) {
        return new AddressHistoryPageResponse(
                pageResult.getContent().stream().map(AddressHistoryResponse::from).toList(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.hasNext()
        );
    }
}
