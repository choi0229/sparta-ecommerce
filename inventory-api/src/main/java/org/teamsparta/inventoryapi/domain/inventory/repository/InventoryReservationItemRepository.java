package org.teamsparta.inventoryapi.domain.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservationItem;

public interface InventoryReservationItemRepository extends JpaRepository<InventoryReservationItem, Long> {
}
