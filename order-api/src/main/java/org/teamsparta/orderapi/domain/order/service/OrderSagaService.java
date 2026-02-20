package org.teamsparta.orderapi.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.event.InventoryConfirmRequestedEvent;
import org.teamsparta.orderapi.domain.order.event.OrderEventPublisher;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmedResult;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.payment.event.PaymentFailedEvent;
import org.teamsparta.orderapi.domain.payment.event.PaymentRequestedEvent;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReserveFailedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.payment.event.PaymentSucceededEvent;
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
    private final OrderEventPublisher orderEventPublisher;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void onInventoryReserved(InventoryReservedResult event){
        Orders order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        OrderSagaState saga = orderSagaStateRepository.findById(event.sagaId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_SAGA));

        if(saga.getState() == SagaState.INVENTORY_RESERVED){
            return;
        }

        saga.updateState(SagaState.INVENTORY_RESERVED, null, event.reservationId());
        order.updateStatus(Status.RESERVED);

        // 결제 요청
        PaymentRequestedEvent paymentRequest = PaymentRequestedEvent.from(saga.getOrderId(), saga.getSagaId(), order.getUserId(), order.getPayAmount());
        saga.updateState(SagaState.PAYMENT_REQUESTED, null, event.reservationId());

        String payload;
        try{
            payload = objectMapper.writeValueAsString(paymentRequest);
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }

        outboxEventRepository.save(OutboxEvent.pending("Orders", order.getId().toString(), "payment-request-event", payload));
    }

    @Transactional
    public void onInventoryReserveFailed(InventoryReserveFailedResult event){
        Orders order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));

        OrderSagaState saga = orderSagaStateRepository.findById(event.sagaId())
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_SAGA));

        if(saga.getState() == SagaState.FAILED){
            return;
        }

        saga.updateState(SagaState.FAILED, null, saga.getReservationId());
        order.updateStatus(Status.FAILED);
    }

    @Transactional
    public void onPaymentSucceeded(PaymentSucceededEvent event){
        Orders order = orderRepository.findById(event.orderId()).orElseThrow();
        OrderSagaState saga = orderSagaStateRepository.findById(event.sagaId()).orElseThrow();

        if (saga.getState() == SagaState.COMPLETED) return;

        // 1. Saga 및 주문 상태 최종 완료
        saga.updateState(SagaState.PAYMENT_COMPLETED, null, saga.getReservationId());
        order.updateStatus(Status.PAID);

        // 재고 감소 및 확정 요청
        InventoryConfirmRequestedEvent inventoryConfirmRequestedEvent = InventoryConfirmRequestedEvent.from(saga.getOrderId(), saga.getSagaId(), saga.getReservationId());

        String payload;
        try{
            payload = objectMapper.writeValueAsString(inventoryConfirmRequestedEvent);
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }

        outboxEventRepository.save(OutboxEvent.pending("Orders", order.getId().toString(), "order-confirm-event", payload));
    }

    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event){
        Orders order = orderRepository.findById(event.orderId()).orElseThrow();
        OrderSagaState saga = orderSagaStateRepository.findById(event.sagaId()).orElseThrow();

        if (saga.getState() == SagaState.FAILED) return;

        saga.updateState(SagaState.FAILED, null, saga.getReservationId());
        order.updateStatus(Status.FAILED);
    }

    @Transactional
    public void onInventoryConfirmed(InventoryConfirmedResult event) {
        Orders order = orderRepository.findById(event.orderId()).orElseThrow();
        OrderSagaState saga = orderSagaStateRepository.findById(event.sagaId()).orElseThrow();

        // 최종 완료 처리
        saga.updateState(SagaState.COMPLETED, null, saga.getReservationId());
        order.updateStatus(Status.COMPLETED);

        log.info("Saga Fully Completed for Order: {}", order.getId());
    }
}
