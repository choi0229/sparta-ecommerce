package org.teamsparta.inventoryapi.domain.inventory.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    Optional<InventoryReservation> findByOrderId(Long orderId);

    @Query(value = """
        SELECT id
        FROM inventory_reservation
        WHERE status = 'RESERVED'
          AND expires_at <= :now
        ORDER BY expires_at ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<UUID> lockExpiredReservations(@Param("now") ZonedDateTime now,
                                                       @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from InventoryReservation r where r.id = :id")
    Optional<InventoryReservation> findByIdForUpdate(@Param("id") UUID id);

}
