package org.teamsparta.orderapi.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.orderapi.domain.order.entity.OrderItem;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
