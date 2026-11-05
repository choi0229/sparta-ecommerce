package org.teamsparta.orderapi.domain.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import org.teamsparta.orderapi.domain.order.dto.response.AcceptedOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.CreateOrderResponse;
import org.teamsparta.orderapi.domain.order.dto.response.OrderStatusResponse;
import org.teamsparta.orderapi.domain.order.entity.*;
import org.teamsparta.orderapi.domain.order.event.OrderCreatedEvent;
import org.teamsparta.orderapi.domain.order.event.OrderEventPublisher;
import org.teamsparta.orderapi.domain.order.event.ProductSnapShotRequestEvent;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult.ProductSnapshotItem;
import org.teamsparta.orderapi.domain.order.client.AddressServiceClient;
import org.teamsparta.orderapi.domain.order.repository.*;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
    private final AddressServiceClient addressServiceClient;

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String SHIPMENT_EVENT_KEY_PREFIX = "shipment-event:";

    /**
     * 처리 순서: 기존 기록 조회 → 주소 조회(트랜잭션 밖) → 저장 트랜잭션.
     * 해시는 클라이언트 입력값으로 계산하므로, 주소가 변경·삭제되어도 같은 요청의 재전송은 기존 결과를 받는다.
     */
    public AcceptedOrderResponse createOrder(CreateOrderRequest request, String idemKey){
        validateIdempotencyKey(idemKey);
        if(request.items() == null || request.items().isEmpty()){
            throw new DomainException(DomainExceptionCode.NOT_FOUND_ITEMS);
        }
        validateItems(request.items());

        String requestHash = hashRequest(request);
        Optional<IdempotencyRecord> existing = idempotencyRepository.findById(idemKey);
        if (existing.isPresent()) {
            return acceptedResponseOf(existing.get(), requestHash);
        }

        CreateOrderRequest.ShippingAddress resolvedAddress = resolveShippingAddress(request);

        ProductSnapShotRequestEvent event = ProductSnapShotRequestEvent.from(UUID.randomUUID(), request.items(), idemKey, request.userId(), resolvedAddress);
        String payload;
        try{
            payload = objectMapper.writeValueAsString(event);
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }

        try {
            orderTransactionalService.acceptOrder(idemKey, requestHash, payload);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 커밋했다. 실패한 트랜잭션은 이미 끝났으므로 새 트랜잭션으로 기존 기록을 읽는다.
            IdempotencyRecord winner = idempotencyRepository.findById(idemKey).orElseThrow(() -> e);
            return acceptedResponseOf(winner, requestHash);
        }
        return acceptedResponse(idemKey);
    }

    private void validateIdempotencyKey(String idemKey) {
        if (idemKey == null || idemKey.isBlank()
                || idemKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH
                || idemKey.startsWith(SHIPMENT_EVENT_KEY_PREFIX)) {
            throw new DomainException(DomainExceptionCode.INVALID_IDEMPOTENCY_KEY);
        }
    }

    private void validateItems(List<CreateOrderRequest.Item> items) {
        Set<String> skus = new HashSet<>();
        for (CreateOrderRequest.Item item : items) {
            if (item == null || item.sku() == null || item.sku().isBlank()) {
                throw new DomainException(DomainExceptionCode.INVALID_SKU);
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
            }
            if (!skus.add(item.sku().trim())) {
                throw new DomainException(DomainExceptionCode.DUPLICATE_SKU);
            }
        }
    }

    private AcceptedOrderResponse acceptedResponseOf(IdempotencyRecord record, String requestHash) {
        if (!record.getRequestHash().equals(requestHash)) {
            throw new DomainException(DomainExceptionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
        }
        return acceptedResponse(record.getIdemKey());
    }

    private AcceptedOrderResponse acceptedResponse(String idemKey) {
        return new AcceptedOrderResponse(idemKey, "주문이 접수되었습니다.");
    }

    /** 주문의 의미를 결정하는 입력을 정규화해 해시한다. addressId가 있으면 직접 입력 주소보다 우선한다. */
    private String hashRequest(CreateOrderRequest request) {
        String items = request.items().stream()
                .map(item -> canonical(item.sku()) + "*" + item.quantity())
                .sorted()
                .collect(Collectors.joining(","));
        String address;
        if (request.addressId() != null) {
            address = "id:" + request.addressId();
        } else if (request.shippingAddress() != null) {
            address = "direct:" + canonical(request.shippingAddress().recipientName())
                    + canonical(request.shippingAddress().recipientAddress());
        } else {
            address = "none";
        }
        String raw = "user=" + request.userId() + "|items=" + items + "|address=" + address;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /** 공백을 정리하고 길이를 붙여, 값의 경계가 달라도 같은 문자열이 되지 않게 한다. */
    private String canonical(String value) {
        if (value == null) {
            return "null;";
        }
        String trimmed = value.trim();
        return trimmed.length() + ":" + trimmed + ";";
    }

    private CreateOrderRequest.ShippingAddress resolveShippingAddress(CreateOrderRequest request) {
        if (request.addressId() != null && request.shippingAddress() != null) {
            log.warn("addressId와 shippingAddress가 동시에 전달됨. addressId 우선 사용. addressId={}", request.addressId());
        }
        if (request.addressId() != null) {
            AddressServiceClient.AddressInfo info = addressServiceClient.findById(request.addressId(), request.userId());
            return new CreateOrderRequest.ShippingAddress(info.recipientName(), info.recipientAddress());
        }
        if (request.shippingAddress() != null) {
            return request.shippingAddress();
        }
        throw new DomainException(DomainExceptionCode.SHIPPING_ADDRESS_REQUIRED);
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
