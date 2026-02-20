package org.teamsparta.orderapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.orderapi.domain.order.entity.OrderSagaState;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.entity.OutboxEvent;
import org.teamsparta.orderapi.domain.order.event.InventoryConfirmRequestedEvent;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryConfirmedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReserveFailedResult;
import org.teamsparta.orderapi.domain.order.event.dto.InventoryReservedResult;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.order.repository.OutboxEventRepository;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.payment.event.PaymentFailedEvent;
import org.teamsparta.orderapi.domain.payment.event.PaymentRequestedEvent;
import org.teamsparta.orderapi.domain.payment.event.PaymentSucceededEvent;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.enums.Status;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
public class OrderSagaServiceTest {

    @InjectMocks
    private OrderSagaService orderSagaService;

    @Mock
    private OrderSagaStateRepository orderSagaStateRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ObjectMapper objectMapper;

    private Orders order;
    private OrderSagaState sagaState;
    private final Long ORDER_ID = 1L;
    private final UUID SAGA_ID = UUID.randomUUID();
    private final UUID RESERVATION_ID = UUID.randomUUID();

    @BeforeEach
    void setUp(){
        order = Orders.createNew("0123", 1L);
        ReflectionTestUtils.setField(order, "id", ORDER_ID);

        sagaState = OrderSagaState.start(SAGA_ID, ORDER_ID);
    }

    @Test
    @DisplayName("재고 예약 성공 시 -> 결제 요청 outbox 발행 및 주문 상태 reserved 변환")
    void onInventoryReserved_success()throws Exception{
        // given
        InventoryReservedResult event = new InventoryReservedResult(
                UUID.randomUUID(), "inventory.reserved", SAGA_ID, ORDER_ID, RESERVATION_ID, null,null
        );
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(orderSagaStateRepository.findById(SAGA_ID)).willReturn(Optional.of(sagaState));
        given(objectMapper.writeValueAsString(any(PaymentRequestedEvent.class))).willReturn("{\"ok\":true}");

        // when
        orderSagaService.onInventoryReserved(event);

        // then
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();

        assertThat(outbox.getAggregateType()).isEqualTo("Orders");
        assertThat(outbox.getAggregateId()).isEqualTo(ORDER_ID.toString());
        assertThat(outbox.getEventType()).isEqualTo("payment-request-event");
        assertThat(outbox.getPayload()).isEqualTo("{\"ok\":true}");

        assertThat(sagaState.getState()).isEqualTo(SagaState.PAYMENT_REQUESTED);
        assertThat(sagaState.getReservationId()).isEqualTo(RESERVATION_ID);
        assertThat(order.getStatus()).isEqualTo(Status.RESERVED);
    }

    @Test
    @DisplayName("재고 저장 실패 - FAILED 전이 + 주문 FAILED")
    void onInventoryReservedFailed_success(){
        // given
        InventoryReserveFailedResult event = new InventoryReserveFailedResult(
                UUID.randomUUID(), "inventory.failed", SAGA_ID, ORDER_ID, "OUT_OF_STOCK"
        );
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(orderSagaStateRepository.findById(SAGA_ID)).willReturn(Optional.of(sagaState));

        // when
        orderSagaService.onInventoryReserveFailed(event);

        // then
        assertThat(sagaState.getState()).isEqualTo(SagaState.FAILED);
        assertThat(order.getStatus()).isEqualTo(Status.FAILED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("결제 성공 - 재고 확정 요청 outbox 발행 및 주문 상태 paid 변경")
    void onPaymentSucceeded_success()throws Exception{
        // given
        ReflectionTestUtils.setField(sagaState, "reservationId", RESERVATION_ID);
        ReflectionTestUtils.setField(sagaState, "state", SagaState.PAYMENT_REQUESTED);

        PaymentSucceededEvent event = PaymentSucceededEvent.from(PaymentRequestedEvent.from(ORDER_ID, SAGA_ID, 1L, java.math.BigDecimal.valueOf(1000)));
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(orderSagaStateRepository.findById(SAGA_ID)).willReturn(Optional.of(sagaState));
        given(objectMapper.writeValueAsString(any(InventoryConfirmRequestedEvent.class))).willReturn("{\"confirm\":true}");

        // when
        orderSagaService.onPaymentSucceeded(event);

        // then
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent outbox = captor.getValue();

        assertThat(outbox.getEventType()).isEqualTo("order-confirm-event");
        assertThat(outbox.getAggregateId()).isEqualTo(ORDER_ID.toString());
        assertThat(outbox.getPayload()).isEqualTo("{\"confirm\":true}");

        assertThat(order.getStatus()).isEqualTo(Status.PAID);
        assertThat(sagaState.getState()).isEqualTo(SagaState.PAYMENT_COMPLETED);
    }

    @Test
    @DisplayName("주문 실패 - FAILED 전이 + 주문 FAILED")
    void onPaymentFailed_success(){
        // given
        ReflectionTestUtils.setField(sagaState, "reservationId", RESERVATION_ID);
        ReflectionTestUtils.setField(sagaState, "state", SagaState.PAYMENT_REQUESTED);
        PaymentFailedEvent event = PaymentFailedEvent.from(PaymentRequestedEvent.from(ORDER_ID, SAGA_ID, 1L, java.math.BigDecimal.valueOf(1000)), "DECLINED");

        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(orderSagaStateRepository.findById(SAGA_ID)).willReturn(Optional.of(sagaState));

        // when
        orderSagaService.onPaymentFailed(event);

        // then
        assertThat(sagaState.getState()).isEqualTo(SagaState.FAILED);
        assertThat(order.getStatus()).isEqualTo(Status.FAILED);
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("재고 성공 - 최종 COMPLETED 전이 + 주문 COMPLETED")
    void onInventoryConfirmed_success(){
        // given
        ReflectionTestUtils.setField(sagaState, "reservationId", RESERVATION_ID);
        ReflectionTestUtils.setField(sagaState, "state", SagaState.PAYMENT_COMPLETED);

        InventoryConfirmedResult event = new InventoryConfirmedResult(
                UUID.randomUUID(), "inventory.confirmed", ORDER_ID, SAGA_ID
        );

        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
        given(orderSagaStateRepository.findById(SAGA_ID)).willReturn(Optional.of(sagaState));

        // when
        orderSagaService.onInventoryConfirmed(event);

        // then
        assertThat(sagaState.getState()).isEqualTo(SagaState.COMPLETED);
        assertThat(order.getStatus()).isEqualTo(Status.COMPLETED);
        verify(outboxEventRepository, never()).save(any());
    }
}
