package org.teamsparta.orderapi.domain.order.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.AcceptedOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.service.OrderService;
import org.teamsparta.orderapi.global.response.ApiResponse;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<AcceptedOrderResponse> createOrder(@RequestBody CreateOrderRequest request){
        String generatedKey = UUID.randomUUID().toString();
        // return ApiResponse.ok(orderService.createOrder(request, generatedKey));
        orderService.createOrder(request, generatedKey);
        return ApiResponse.ok(new AcceptedOrderResponse(generatedKey, "주문이 접수되었습니다."));
    }

    @GetMapping("/status/{idemKey}")
    public ApiResponse<OrderStatusResponse> getOrderStatus(@PathVariable String idemKey) {
        return ApiResponse.ok(orderService.getOrderStatus(idemKey));
    }
}
