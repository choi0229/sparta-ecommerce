package org.teamsparta.inventoryapi.domain.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;

import java.util.Optional;
import java.util.UUID;

public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {
    Optional<InventoryReservation> findByOrderId(Long orderId);
}
