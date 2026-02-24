package org.teamsparta.orderapi.domain.order.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.service.OrderService;
import org.teamsparta.orderapi.global.response.ApiResponse;

import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<CreateOrderResponse> createOrder(@RequestBody CreateOrderRequest request){
        String generatedKey = UUID.randomUUID().toString();
        return ApiResponse.ok(orderService.createOrder(request, generatedKey));
    }
}
