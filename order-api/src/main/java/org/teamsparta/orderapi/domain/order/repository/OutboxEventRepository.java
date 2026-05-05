package org.teamsparta.orderapi.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.global.enums.OutboxStatus;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    long countByStatus(OutboxStatus status);
}
