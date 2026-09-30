package org.teamsparta.orderapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.repository.IdempotencyRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.service.OrderStatusReader;
import org.teamsparta.orderapi.global.enums.Status;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderStatusReaderTest {

    @Mock
    private IdempotencyRepository idempotencyRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderStatusReader orderStatusReader;

    private static final String IDEM_KEY = "idem-key-1";
    private static final Long ORDER_ID = 1L;

    @Test
    @DisplayName("멱등성 레코드가 없으면 빈 Optional을 반환하고 주문은 조회하지 않는다")
    void read_noRecord() {
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.empty());

        Optional<OrderStatusResponse> result = orderStatusReader.read(IDEM_KEY);

        assertThat(result).isEmpty();
        verify(orderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("레코드는 있지만 orderId가 없으면 orderStatus는 null이고 주문은 조회하지 않는다")
    void read_recordWithoutOrderId() {
        IdempotencyRecord record = IdempotencyRecord.start(IDEM_KEY, "hash");
        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(record));

        Optional<OrderStatusResponse> result = orderStatusReader.read(IDEM_KEY);

        assertThat(result).contains(new OrderStatusResponse(IDEM_KEY, "PENDING", null, null));
        verify(orderRepository, never()).findById(any());
    }

    @Test
    @DisplayName("orderId가 있으면 주문을 조회해 진행 상태를 함께 반환한다")
    void read_withOrder() {
        IdempotencyRecord record = IdempotencyRecord.start(IDEM_KEY, "hash");
        record.complete(ORDER_ID);
        Orders order = Orders.createNew("ORD-001", 1L);
        order.updateStatus(Status.PAID);

        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(record));
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));

        Optional<OrderStatusResponse> result = orderStatusReader.read(IDEM_KEY);

        assertThat(result).contains(new OrderStatusResponse(IDEM_KEY, "COMPLETED", ORDER_ID, "PAID"));
    }

    @Test
    @DisplayName("orderId는 있는데 주문이 없으면 NOT_FOUND_ORDER 예외를 던진다")
    void read_orderNotFound() {
        IdempotencyRecord record = IdempotencyRecord.start(IDEM_KEY, "hash");
        record.complete(ORDER_ID);

        given(idempotencyRepository.findById(IDEM_KEY)).willReturn(Optional.of(record));
        given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderStatusReader.read(IDEM_KEY))
                .isInstanceOf(DomainException.class)
                .extracting("code")
                .isEqualTo(DomainExceptionCode.NOT_FOUND_ORDER.name());
    }
}