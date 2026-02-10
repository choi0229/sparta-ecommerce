package org.teamsparta.orderapi.domain.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.enums.Status;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaService {

    private final OrderRepository orderRepository;
    private final OrderSagaStateRepository orderSagaStateRepository;

    @Transactional
    public void onInventoryReserved(InventoryReservedResult event){
        Orders order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        OrderSagaState saga = orderSagaStateRepository.findById(event.sagaId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_SAGA));

        if(saga.getState() == SagaState.INVENTORY_RESERVE){
            return;
        }

        saga.update(SagaState.INVENTORY_RESERVE, null);
        order.updateStatus(Status.RESERVED);
    }
}
