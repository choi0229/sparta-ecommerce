package org.teamsparta.orderapi.domain.order.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.service.OrderService;
import org.teamsparta.orderapi.global.response.ApiResponse;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ApiResponse<Void> createOrder(@RequestBody CreateOrderRequest request){
        return null;
    }
}
