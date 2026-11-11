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
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmFailedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservationExpiredResult;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventRecorder;
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

import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSagaService {

    private final OrderRepository orderRepository;
    private final OrderSagaStateRepository orderSagaStateRepository;
    private final OrderEventPublisher orderEventPublisher;
    private final OutboxEventRecorder outboxEventRecorder;
    private final ObjectMapper objectMapper;

    private static final String RESERVATION_EXPIRED_AFTER_PAYMENT = "RESERVATION_EXPIRED_AFTER_PAYMENT";
    private static final String TTL_EXPIRED = "TTL_EXPIRED";

    /*
     * 상태 전이 (출발 상태는 orders.status로 판단하고, 주문 행 잠금으로 토픽 간 처리를 직렬화한다)
     *   inventory-reserved-event        CREATED  → RESERVED
     *   inventory-failed-event          CREATED  → FAILED
     *   payment-succeeded-event         RESERVED → PAID
     *   payment-failed-event            RESERVED → FAILED
     *   inventory-confirm-event         PAID     → COMPLETED
     *   inventory-confirm-failed-event  PAID     → FAILED
     *   inventory-expired-event         RESERVED → EXPIRED (PAID는 확정 결과를 기다리며 유지)
     * 이미 처리된 상태면 중복(info), 그 외는 순서 오류(warn)로 기록하고 상태를 바꾸지 않는다.
     */

    @Transactional
    public void onInventoryReserved(InventoryReservedResult event){
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        if(!canTransition(order, "inventory-reserved-event", Status.CREATED,
                Status.RESERVED, Status.PAID, Status.COMPLETED)){
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

        outboxEventRecorder.save(OutboxEvent.pending("Orders", order.getId().toString(), "payment-request-event", payload));
    }

    @Transactional
    public void onInventoryReserveFailed(InventoryReserveFailedResult event){
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        if(!canTransition(order, "inventory-failed-event", Status.CREATED, Status.FAILED)){
            return;
        }

        saga.updateState(SagaState.FAILED, event.message(), saga.getReservationId());
        order.markFailed(event.message());
    }

    @Transactional
    public void onPaymentSucceeded(PaymentSucceededEvent event){
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        // TODO: 결제가 끝났는데 주문이 FAILED/EXPIRED로 끝난 경우 환불·결제 취소 보상이 필요하다
        if(!canTransition(order, "payment-succeeded-event", Status.RESERVED, Status.PAID, Status.COMPLETED)){
            return;
        }

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

        outboxEventRecorder.save(OutboxEvent.pending("Orders", order.getId().toString(), "order-confirm-event", payload));
    }

    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event){
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        if(!canTransition(order, "payment-failed-event", Status.RESERVED, Status.FAILED)){
            return;
        }

        saga.updateState(SagaState.FAILED, event.reason(), saga.getReservationId());
        order.markFailed(event.reason());
    }

    @Transactional
    public void onInventoryConfirmed(InventoryConfirmedResult event) {
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        if(!canTransition(order, "inventory-confirm-event", Status.PAID, Status.COMPLETED)){
            return;
        }

        // 최종 완료 처리
        saga.updateState(SagaState.COMPLETED, null, saga.getReservationId());
        order.updateStatus(Status.COMPLETED);

        log.info("Saga Fully Completed for Order: {}", order.getId());
    }

    @Transactional
    public void onInventoryConfirmFailed(InventoryConfirmFailedResult event) {
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        if(!canTransition(order, "inventory-confirm-failed-event", Status.PAID, Status.FAILED)){
            return;
        }

        // TODO: 결제가 끝난 주문이므로 환불·결제 취소 보상이 필요하다
        saga.updateState(SagaState.FAILED, RESERVATION_EXPIRED_AFTER_PAYMENT, saga.getReservationId());
        order.markFailed(RESERVATION_EXPIRED_AFTER_PAYMENT);
    }

    @Transactional
    public void onInventoryExpired(InventoryReservationExpiredResult event){
        Orders order = lockOrder(event.orderId());
        OrderSagaState saga = findSaga(event.sagaId());

        // 결제가 끝난 주문은 확정 요청이 이미 나갔으므로, 확정 성공 또는 확정 실패 이벤트로 결과가 정해진다
        if(order.getStatus() == Status.PAID){
            log.info("inventory-expired-event ignored: waiting for confirm result. orderId={}, sagaId={}",
                    event.orderId(), event.sagaId());
            return;
        }
        if(!canTransition(order, "inventory-expired-event", Status.RESERVED, Status.EXPIRED)){
            return;
        }

        if(saga.getReservationId() != null && !saga.getReservationId().equals(event.reservationId())){
            throw new DomainException(DomainExceptionCode.INVALID_RESERVATION_ID);
        }

        saga.updateState(SagaState.EXPIRED, TTL_EXPIRED, event.reservationId());
        order.markExpired(TTL_EXPIRED);
    }

    private Orders lockOrder(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));
    }

    private OrderSagaState findSaga(UUID sagaId) {
        return orderSagaStateRepository.findById(sagaId)
                .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_SAGA));
    }

    private boolean canTransition(Orders order, String eventType, Status from, Status... alreadyApplied) {
        Status current = order.getStatus();
        if (current == from) {
            return true;
        }
        if (Arrays.asList(alreadyApplied).contains(current)) {
            log.info("Duplicate {} ignored. orderId={}, status={}", eventType, order.getId(), current);
        } else {
            log.warn("Out-of-order {} ignored. orderId={}, status={}, expected={}", eventType, order.getId(), current, from);
        }
        return false;
    }
}
