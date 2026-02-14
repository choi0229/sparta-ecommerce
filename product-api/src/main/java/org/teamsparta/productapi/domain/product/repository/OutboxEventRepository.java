package org.teamsparta.productapi.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent,Long> {
}
