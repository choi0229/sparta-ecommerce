package org.teamsparta.addressapi.domain.address.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;

import java.util.List;

public interface UserAddressHistoryRepository extends JpaRepository<UserAddressHistory, Long> {

    List<UserAddressHistory> findByAddressIdOrderByCreatedAtDesc(Long addressId);
}
