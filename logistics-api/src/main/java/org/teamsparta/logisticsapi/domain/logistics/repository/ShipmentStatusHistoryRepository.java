package org.teamsparta.logisticsapi.domain.logistics.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.logisticsapi.domain.logistics.entity.ShipmentStatusHistory;

import java.util.List;

public interface ShipmentStatusHistoryRepository extends JpaRepository<ShipmentStatusHistory, Long> {
    List<ShipmentStatusHistory> findByShipmentIdOrderByOccurredAtAsc(Long shipmentId);
}
