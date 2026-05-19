package org.teamsparta.addressapi.domain.address.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryPageResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.repository.UserAddressHistoryRepository;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAddressHistoryService {

    private final UserAddressHistoryRepository userAddressHistoryRepository;

    @Transactional(readOnly = true)
    public AddressHistoryPageResponse searchHistories(
            Long userId, Long addressId, UserAddressHistory.ActionType actionType,
            LocalDateTime from, LocalDateTime to, int page, int size) {

        if (page < 0 || size <= 0 || size > 100) {
            throw new IllegalArgumentException("page >= 0, 0 < size <= 100");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserAddressHistory> result = userAddressHistoryRepository
                .findAllByFilters(userId, addressId, actionType, from, to, pageable);
        return AddressHistoryPageResponse.from(result);
    }
}
