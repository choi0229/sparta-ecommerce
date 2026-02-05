package org.teamsparta.orderapi.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.entity.IdempotencyRecord;
import org.teamsparta.orderapi.domain.order.entity.OrderItem;
import org.teamsparta.orderapi.domain.order.entity.Orders;
import org.teamsparta.orderapi.domain.order.event.OrderEventPublisher;
import org.teamsparta.orderapi.domain.order.repository.OrderItemRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderRepository;
import org.teamsparta.orderapi.domain.order.repository.OrderSagaStateRepository;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderSagaStateRepository sagaStateRepository;

    private final OrderEventPublisher orderEventPublisher;
    private final ProductProjectionRepository productProjectionRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request, String idemKey) {
        if(request.items() == null || request.items().isEmpty()){
            throw new DomainException(DomainExceptionCode.NOT_FOUND_ITEMS);
        }

        List<CreateOrderRequest.Item> items = request.items();

        List<String> skus = items.stream()
                .map(CreateOrderRequest.Item::sku)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if(skus.size() != items.size()){
            throw new DomainException(DomainExceptionCode.INVALID_SKU);
        }

        String requestHash = hashRequest(request);
        IdempotencyRecord idemRecord = idempotencyService.startOrThrow(idemKey, requestHash);

        if("COMPLETED".equals(idemRecord.getStatus().name()) && idemRecord.getOrderId() != null){
            Orders existing = orderRepository.findById(idemRecord.getOrderId())
                    .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));
            return new CreateOrderResponse(existing.getId(), existing.getOrderNo(), existing.getStatus());
        }

        List<ProductProjection> projections = productProjectionRepository.findBySkuIn(skus);
        Map<String, ProductProjection> bySku = projections.stream()
                .collect(Collectors.toMap(ProductProjection::getSku, p -> p));

        List<String> missing = skus.stream()
                .filter(sku -> !bySku.containsKey(sku))
                .distinct()
                .toList();

        if (!missing.isEmpty()) {
            // TODO: 실패 처리 + "잠시 후 다시 시도"
            throw new DomainException(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY);
        }

        String orderNo = generateOrderNo();
        Orders order = Orders.createNew(orderNo, request.userId());
        Orders savedOrder = orderRepository.save(order);

        // TODO:saga_state

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        for(CreateOrderRequest.Item item : items){
            if(item.quantity() == null || item.quantity() <= 0){
                throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
            }

            ProductProjection productProjection = bySku.get(item.sku());

            if(!"ACTIVE".equals(productProjection.getStatus().name())){
                throw new DomainException(DomainExceptionCode.PRODUCT_INACTIVE);
            }

            BigDecimal unitPrice = productProjection.getPrice();
            BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            total = total.add(lineAmount);

            Map<String, Object> optionJsonSnapShot = productProjection.getOptionJson();

            OrderItem orderItem = OrderItem.of(
                    savedOrder.getId(),
                    productProjection.getSku(),
                    item.quantity(),
                    unitPrice,
                    productProjection.getProductName(),
                    null,
                    optionJsonSnapShot,
                    productProjection.getCategoryPath()
            );
            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);

        // TODO : 재고 처리 및 결제 후 outbox

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                idempotencyService.complete(idemKey, savedOrder.getId());
            }
        });
        return null;
    }

    private String generateOrderNo() {
        return "O" + System.currentTimeMillis();
    }

    // TODO : SHA-256으로 교체
    private String hashRequest(CreateOrderRequest request) {
        String raw = request.userId() + "|" + request.items().stream()
                .map(i -> i.sku() + ":" + i.quantity())
                .sorted()
                .collect(Collectors.joining(","));
        return Integer.toHexString(raw.hashCode());
    }
}
