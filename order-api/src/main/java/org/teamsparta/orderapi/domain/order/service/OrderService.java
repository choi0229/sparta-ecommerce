package org.teamsparta.orderapi.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.*;
import org.teamsparta.orderapi.domain.order.event.OrderCreatedEvent;
import org.teamsparta.orderapi.domain.order.event.OrderEventPublisher;
import org.teamsparta.orderapi.domain.order.event.ProductSnapShotRequestEvent;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult.ProductSnapshotItem;
import org.teamsparta.orderapi.domain.order.repository.*;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ProductSnapshotPendingStore productSnapshotPendingStore;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderTransactionalService orderTransactionalService;
    private final IdempotencyRepository idempotencyRepository;

    public void createOrder(CreateOrderRequest request, String idemKey){
        if(request.items() == null || request.items().isEmpty()){
            throw new DomainException(DomainExceptionCode.NOT_FOUND_ITEMS);
        }

        ProductSnapShotRequestEvent event = ProductSnapShotRequestEvent.from(UUID.randomUUID(), request.items(), idemKey, request.userId());
        String payload;

        try{
            payload = objectMapper.writeValueAsString(event);
            outboxEventRepository.save(OutboxEvent.pending("Orders", UUID.randomUUID().toString(), "productSnapshot-requested-event", payload));
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
    }

    @Transactional(readOnly = true)
    public OrderStatusResponse getOrderStatus(String idemKey) {
        Optional<IdempotencyRecord> recordOpt = idempotencyRepository.findById(idemKey);
        if (recordOpt.isEmpty()) {
            return new OrderStatusResponse(idemKey, "PENDING", null, null, null);
        }
        IdempotencyRecord record = recordOpt.get();
        String status = record.getStatus().name();
        String shipmentStatus = null;
        if (record.getOrderId() != null) {
            Optional<Orders> orderOpt = orderRepository.findById(record.getOrderId());
            if (orderOpt.isPresent()) {
                Orders order = orderOpt.get();
                status = order.getStatus().name();
                shipmentStatus = order.getShipmentStatus() != null ? order.getShipmentStatus().name() : null;
            } else {
                log.warn("orderId={} found in IdempotencyRecord but Orders not found. idemKey={}", record.getOrderId(), idemKey);
            }
        }
        return new OrderStatusResponse(record.getIdemKey(), status, record.getOrderId(), shipmentStatus, record.getFailureReason());
    }

//    @Transactional
//    public CreateOrderResponse createOrder(CreateOrderRequest request, String idemKey) {
//        if(request.items() == null || request.items().isEmpty()){
//            throw new DomainException(DomainExceptionCode.NOT_FOUND_ITEMS);
//        }
//
//        List<CreateOrderRequest.Item> items = request.items();
//
//        List<String> skus = items.stream()
//                .map(CreateOrderRequest.Item::sku)
//                .filter(Objects::nonNull)
//                .map(String::trim)
//                .filter(s -> !s.isEmpty())
//                .toList();
//
//        if(skus.size() != items.size()){
//            throw new DomainException(DomainExceptionCode.INVALID_SKU);
//        }
//
//        String requestHash = hashRequest(request);
//        IdempotencyRecord idemRecord = idempotencyService.startOrThrow(idemKey, requestHash);
//
//        if("COMPLETED".equals(idemRecord.getStatus().name()) && idemRecord.getOrderId() != null){
//            Orders existing = orderRepository.findById(idemRecord.getOrderId())
//                    .orElseThrow(() -> new DomainException(DomainExceptionCode.NOT_FOUND_ORDER));
//            return new CreateOrderResponse(existing.getId(), existing.getOrderNo(), existing.getStatus());
//        }
//
////        List<ProductProjection> projections = productProjectionRepository.findBySkuIn(skus);
////        Map<String, ProductProjection> bySku = projections.stream()
////                .collect(Collectors.toMap(ProductProjection::getSku, p -> p));
//        Map<String, ProductSnapshotItem> bySku = fetchBySkus(skus, UUID.randomUUID());
//
//        List<String> missing = skus.stream()
//                .filter(sku -> !bySku.containsKey(sku))
//                .distinct()
//                .toList();
//        if(!missing.isEmpty()){
//            throw new DomainException(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY);
//        }
//
//        String orderNo = generateOrderNo();
//        Orders order = Orders.createNew(orderNo, request.userId());
//        Orders savedOrder = orderRepository.save(order);
//
//        // TODO:saga_state
//        OrderSagaState sagaState = OrderSagaState.start(order.getSagaId(), savedOrder.getId());
//        sagaStateRepository.save(sagaState);
//
//        BigDecimal total = BigDecimal.ZERO;
//        List<OrderItem> orderItems = new ArrayList<>();
//        for(CreateOrderRequest.Item item : items){
//            if(item.quantity() == null || item.quantity() <= 0){
//                throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
//            }
//
//            ProductSnapshotItem snap = bySku.get(item.sku());
//
//            BigDecimal unitPrice = snap.price();
//            BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
//            total = total.add(lineAmount);
//
//            Map<String, Object> optionJsonSnapShot = snap.optionJson();
//
//            OrderItem orderItem = OrderItem.of(
//                    savedOrder.getId(),
//                    snap.sku(),
//                    item.quantity(),
//                    unitPrice,
//                    snap.productName(),
//                    null,
//                    optionJsonSnapShot,
//                    snap.productId().toString()
//            );
//            orderItems.add(orderItem);
//        }
//        orderItemRepository.saveAll(orderItems);
//        sagaState.updateState(SagaState.INVENTORY_RESERVE_REQUESTED, null, null);
//        sagaStateRepository.save(sagaState);
//
//        OrderCreatedEvent orderCreatedEvent = OrderCreatedEvent.from(order, orderItems);
//        String payload;
//        try{
//            payload = objectMapper.writeValueAsString(orderCreatedEvent);
//        }catch(Exception e){
//            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
//        }
//        outboxEventRepository.save(OutboxEvent.pending("Orders", savedOrder.getId().toString(), "order-create-event", payload));
//        idempotencyService.complete(idemKey, savedOrder.getId());
//
//        return new CreateOrderResponse(savedOrder.getId(), savedOrder.getOrderNo(), savedOrder.getStatus());
//    }



//    private void fetchBySkus(List<String> skus, UUID requestId, CreateOrderRequest orderRequest, String idemKey){
//        ProductSnapShotRequestEvent request = ProductSnapShotRequestEvent.from(requestId, skus, orderRequest, idemKey);
//        // CompletableFuture<ProductSnapshotReplyResult> future = productSnapshotPendingStore.register(requestId);
//
//        try{
//            // kafkaTemplate.send("productSnapshot-requested-event", requestId.toString(), objectMapper.writeValueAsString(request));
//            // ProductSnapshotReplyResult reply = future.get(800, TimeUnit.MILLISECONDS);
//            outboxEventRepository.save(OutboxEvent.pending("Orders",  requestId.toString(), "productSnapshot-requested-event", objectMapper.writeValueAsString(request)));
////            if (!reply.success()) {
////                throw new DomainException(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY);
////            }
////
////            Map<String, ProductSnapshotItem> bySku = reply.items().stream()
////                    .collect(Collectors.toMap(ProductSnapshotItem::sku, it -> it));
////
////            List<String> missing = skus.stream().filter(s -> !bySku.containsKey(s)).distinct().toList();
////            if (!missing.isEmpty()) {
////                throw new DomainException(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY);
////            }
////            return bySku;
//        }catch(Exception e){
//            productSnapshotPendingStore.timeout(requestId);
//            throw new DomainException(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY);
//        }
//    }
}
