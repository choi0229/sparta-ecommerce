package org.teamsparta.logisticsapi.domain.logistics.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
}
