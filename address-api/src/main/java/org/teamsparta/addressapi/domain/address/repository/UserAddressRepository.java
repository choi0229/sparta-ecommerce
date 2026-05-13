package org.teamsparta.addressapi.domain.address.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;

import java.util.List;
import java.util.Optional;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    Optional<UserAddress> findByIdAndDeletedFalse(Long id);

    Optional<UserAddress> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    List<UserAddress> findByUserIdAndDeletedFalse(Long userId);

    // clearAutomatically=true: JPQL bulk update 후 L1 캐시 무효화
    // → 이후 save()가 merge()를 거쳐 정확한 상태로 DB에 반영됨
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserAddress a SET a.isDefault = false WHERE a.userId = :userId AND a.deleted = false")
    void clearDefaultsByUserId(@Param("userId") Long userId);
}
