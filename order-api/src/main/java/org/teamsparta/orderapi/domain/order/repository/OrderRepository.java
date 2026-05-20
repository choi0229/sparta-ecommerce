package org.teamsparta.orderapi.domain.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsparta.orderapi.domain.order.entity.Orders;

public interface OrderRepository extends JpaRepository<Orders, Long> {
}
