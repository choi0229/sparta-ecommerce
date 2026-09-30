package org.teamsparta.orderapi.domain.order.repository;


import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;

import java.util.List;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {
    List<IdempotencyRecord> findAllByOrderId(Long orderId);
}
