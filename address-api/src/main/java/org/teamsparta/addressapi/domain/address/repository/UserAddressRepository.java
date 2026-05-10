package org.teamsparta.addressapi.domain.address.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
}
