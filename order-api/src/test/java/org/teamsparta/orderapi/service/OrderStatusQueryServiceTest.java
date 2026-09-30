package org.teamsparta.orderapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.service.OrderStatusQueryService;
import org.teamsparta.orderapi.domain.order.service.OrderStatusReader;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OrderStatusQueryServiceTest {

    @Mock
    private OrderStatusReader orderStatusReader;

    @InjectMocks
    private OrderStatusQueryService orderStatusQueryService;

    private static final String IDEM_KEY = "idem-key-1";

    @Test
    @DisplayName("DB에 레코드가 없으면 PENDING을 반환한다")
    void getOrderStatus_notFound() {
        given(orderStatusReader.read(IDEM_KEY)).willReturn(Optional.empty());

        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(IDEM_KEY);

        assertThat(response).isEqualTo(new OrderStatusResponse(IDEM_KEY, "PENDING", null, null));
    }

    @Test
    @DisplayName("DB 조회 결과가 있으면 그대로 반환한다")
    void getOrderStatus_found() {
        OrderStatusResponse loaded = new OrderStatusResponse(IDEM_KEY, "COMPLETED", 1L, "PAID");
        given(orderStatusReader.read(IDEM_KEY)).willReturn(Optional.of(loaded));

        OrderStatusResponse response = orderStatusQueryService.getOrderStatus(IDEM_KEY);

        assertThat(response).isEqualTo(loaded);
    }
}