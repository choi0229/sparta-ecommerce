package org.teamsparta.addressapi.domain.address.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;

import java.time.LocalDateTime;

public interface UserAddressHistoryRepository extends JpaRepository<UserAddressHistory, Long> {

    Page<UserAddressHistory> findByAddressId(Long addressId, Pageable pageable);

    Page<UserAddressHistory> findByAddressIdAndActionType(
            Long addressId, UserAddressHistory.ActionType actionType, Pageable pageable);

    @Query(value = """
            SELECT h FROM UserAddressHistory h
            WHERE (:userId IS NULL OR h.userId = :userId)
              AND (:addressId IS NULL OR h.addressId = :addressId)
              AND (:actionType IS NULL OR h.actionType = :actionType)
              AND (:from IS NULL OR h.createdAt >= :from)
              AND (:to IS NULL OR h.createdAt <= :to)
            """,
            countQuery = """
            SELECT COUNT(h) FROM UserAddressHistory h
            WHERE (:userId IS NULL OR h.userId = :userId)
              AND (:addressId IS NULL OR h.addressId = :addressId)
              AND (:actionType IS NULL OR h.actionType = :actionType)
              AND (:from IS NULL OR h.createdAt >= :from)
              AND (:to IS NULL OR h.createdAt <= :to)
            """)
    Page<UserAddressHistory> findAllByFilters(
            @Param("userId") Long userId,
            @Param("addressId") Long addressId,
            @Param("actionType") UserAddressHistory.ActionType actionType,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);
}
