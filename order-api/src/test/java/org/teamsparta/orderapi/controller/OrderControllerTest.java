package org.teamsparta.orderapi.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.orderapi.domain.order.controller.OrderController;
import org.teamsparta.orderapi.domain.order.dto.response.AcceptedOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.service.OrderService;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    private static final String BODY = """
            {"userId":1,"items":[{"sku":"SKU-A","quantity":1}],
             "shippingAddress":{"recipientName":"홍길동","recipientAddress":"서울"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @Test
    @DisplayName("접수 성공 → 202 Accepted와 접수 결과")
    void createOrder_returns202() throws Exception {
        given(orderService.createOrder(any(), eq("key-1")))
                .willReturn(new AcceptedOrderResponse("key-1", "주문이 접수되었습니다."));

        mockMvc.perform(post("/api/orders").header("Idempotency-Key", "key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.idemKey").value("key-1"));
    }

    @Test
    @DisplayName("상태 조회 → 200과 외부 상태, 주문 필드, 배송·실패 필드")
    void getOrderStatus_returns200() throws Exception {
        given(orderService.getOrderStatus("key-1")).willReturn(
                new OrderStatusResponse("key-1", "SUCCEEDED", 10L, "O001", "READY", null));

        mockMvc.perform(get("/api/orders/status/key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idemKey").value("key-1"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.orderId").value(10))
                .andExpect(jsonPath("$.data.orderNo").value("O001"))
                .andExpect(jsonPath("$.data.shipmentStatus").value("READY"));
    }

    @Test
    @DisplayName("기록 없는 키 상태 조회 → 404")
    void getOrderStatus_unknownKey_returns404() throws Exception {
        given(orderService.getOrderStatus("no-key"))
                .willThrow(new DomainException(DomainExceptionCode.NOT_FOUND_IDEMPOTENCY));

        mockMvc.perform(get("/api/orders/status/no-key"))
                .andExpect(status().isNotFound());
    }
}
