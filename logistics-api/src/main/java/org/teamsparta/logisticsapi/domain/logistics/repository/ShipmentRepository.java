package org.teamsparta.logisticsapi.domain.logistics.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.logisticsapi.domain.logistics.entity.Shipment;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {
    boolean existsByOrderId(Long orderId);
    Optional<Shipment> findByOrderId(Long orderId);
}
