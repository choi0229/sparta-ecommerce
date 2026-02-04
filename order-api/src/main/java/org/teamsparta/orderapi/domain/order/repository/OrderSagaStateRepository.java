package org.teamsparta.orderapi.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;

import java.util.UUID;

public interface OrderSagaStateRepository extends JpaRepository<OrderSagaState, UUID> {
}
