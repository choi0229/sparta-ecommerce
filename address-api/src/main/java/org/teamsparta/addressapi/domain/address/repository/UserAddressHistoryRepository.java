package org.teamsparta.addressapi.domain.address.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;

public interface UserAddressHistoryRepository extends JpaRepository<UserAddressHistory, Long> {

    Page<UserAddressHistory> findByAddressId(Long addressId, Pageable pageable);

    Page<UserAddressHistory> findByAddressIdAndActionType(
            Long addressId, UserAddressHistory.ActionType actionType, Pageable pageable);
}
