package org.teamsparta.inventoryapi.domain.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;

import java.util.List;
import java.util.UUID;

public interface InventoryReservationItemRepository extends JpaRepository<InventoryReservationItem, Long> {
    List<InventoryReservationItem> findAllByReservationId(UUID reservationId);
}
