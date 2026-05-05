package org.teamsparta.inventoryapi.domain.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    long countByStatus(OutboxStatus status);
}
