package org.teamsparta.orderapi.domain.order.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.AcceptedOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.service.OrderService;
import org.teamsparta.orderapi.global.response.ApiResponse;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AcceptedOrderResponse> createOrder(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateOrderRequest request){
        return ApiResponse.ok(orderService.createOrder(request, idempotencyKey));
    }

    @GetMapping("/status/{idemKey}")
    public ApiResponse<OrderStatusResponse> getOrderStatus(@PathVariable String idemKey) {
        return ApiResponse.ok(orderService.getOrderStatus(idemKey));
    }
}
