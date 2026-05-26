package org.teamsparta.logisticsapi.domain.logistics.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.logisticsapi.domain.logistics.entity.ShipmentAddressHistory;

import java.util.List;

public interface ShipmentAddressHistoryRepository extends JpaRepository<ShipmentAddressHistory, Long> {
    List<ShipmentAddressHistory> findByShipmentIdOrderByChangedAtAsc(Long shipmentId);
}
