package org.teamsparta.addressapi.domain.address.dto;

import org.springframework.data.domain.Page;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;

import java.util.List;

public record AddressPageResponse(
        List<AddressDetailResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static AddressPageResponse from(Page<UserAddress> pageResult) {
        return new AddressPageResponse(
                pageResult.getContent().stream().map(AddressDetailResponse::from).toList(),
                pageResult.getNumber(),
                pageResult.getSize(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                pageResult.hasNext()
        );
    }
}
