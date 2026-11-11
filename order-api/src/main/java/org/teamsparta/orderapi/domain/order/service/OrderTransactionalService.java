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
import org.teamsparta.orderapi.domain.order.entity.*;
import org.teamsparta.orderapi.domain.order.event.OrderCreatedEvent;
import org.teamsparta.orderapi.domain.order.event.OrderEventPublisher;
import org.teamsparta.orderapi.domain.order.event.ProductSnapShotRequestEvent;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult.ProductSnapshotItem;
import org.teamsparta.orderapi.domain.order.repository.*;
import org.teamsparta.orderapi.domain.order.scheduler.OutboxEventRecorder;
import org.teamsparta.orderapi.domain.productProjection.entity.ProductProjection;
import org.teamsparta.orderapi.domain.productProjection.repository.ProductProjectionRepository;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;
import org.teamsparta.orderapi.global.enums.SagaState;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderTransactionalService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderSagaStateRepository sagaStateRepository;

    private final OrderEventPublisher orderEventPublisher;
    private final ProductProjectionRepository productProjectionRepository;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final OutboxQueryRepository outboxQueryRepository;
    private final OutboxEventRecorder outboxEventRecorder;
    private final ProductSnapshotPendingStore productSnapshotPendingStore;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 멱등 기록과 주문 접수 Outbox를 같은 트랜잭션에 저장한다. 같은 키가 이미 있으면 함께 롤백된다. */
    @Transactional
    public void acceptOrder(String idemKey, String requestHash, String snapshotRequestPayload) {
        idempotencyService.start(idemKey, requestHash);
        outboxEventRecorder.save(OutboxEvent.pending("Orders", UUID.randomUUID().toString(), "productSnapshot-requested-event", snapshotRequestPayload));
    }

    @Transactional
    public void createOrderInternal(ProductSnapshotReplyResult result, String idemKey) {
        IdempotencyRecord idemRecord = idempotencyService.getForUpdate(idemKey);

        if(idemRecord.getStatus() != IdempotencyStatus.PENDING){
            log.info("Duplicate product snapshot reply ignored. idemKey={}, status={}", idemKey, idemRecord.getStatus());
            return;
        }

        // 비즈니스 실패는 주문을 저장하기 전에 판별해 FAILED로 커밋한다 (고아 주문 방지)
        Optional<String> failureReason = findBusinessFailure(result);
        if(failureReason.isPresent()){
            log.warn("Order creation rejected. idemKey={}, reason={}", idemKey, failureReason.get());
            idemRecord.fail(failureReason.get());
            return;
        }

        Map<String, ProductSnapshotItem> bySku = result.items().stream()
                .collect(Collectors.toMap(ProductSnapshotItem::sku, it -> it, (first, second) -> first));

        BigDecimal total = BigDecimal.ZERO;
        for(ProductSnapshotReplyResult.Item item : result.requestItem()){
            total = total.add(bySku.get(item.sku()).price().multiply(BigDecimal.valueOf(item.quantity())));
        }

        String orderNo = generateOrderNo();
        Orders order = Orders.createNew(orderNo, result.userId());
        order.applyTotalAmount(total);
        if (result.shippingAddress() != null) {
            order.setShippingAddress(result.shippingAddress().recipientName(), result.shippingAddress().recipientAddress());
        }
        Orders savedOrder = orderRepository.save(order);

        OrderSagaState sagaState = OrderSagaState.start(order.getSagaId(), savedOrder.getId());
        sagaStateRepository.save(sagaState);

        List<OrderItem> orderItems = new ArrayList<>();
        for(ProductSnapshotReplyResult.Item item : result.requestItem()){
            ProductSnapshotItem snap = bySku.get(item.sku());

            OrderItem orderItem = OrderItem.of(
                    savedOrder.getId(),
                    snap.sku(),
                    item.quantity(),
                    snap.price(),
                    snap.productName(),
                    null,
                    snap.optionJson(),
                    null
            );
            orderItems.add(orderItem);
        }
        orderItemRepository.saveAll(orderItems);
        sagaState.updateState(SagaState.INVENTORY_RESERVE_REQUESTED, null, null);
        sagaStateRepository.save(sagaState);

        OrderCreatedEvent orderCreatedEvent = OrderCreatedEvent.from(order, orderItems);
        String payload;
        try{
            payload = objectMapper.writeValueAsString(orderCreatedEvent);
        }catch(Exception e){
            throw new DomainException(DomainExceptionCode.EVENT_PUBLISH_ERROR);
        }
        outboxEventRecorder.save(OutboxEvent.pending("Orders", savedOrder.getId().toString(), "order-create-event", payload));
        idempotencyService.complete(idemKey, savedOrder.getId());
    }

    /** 스냅샷 응답을 주문으로 만들 수 없는 이유를 찾는다. 접수 단계를 통과한 뒤 이벤트로 들어온 값도 믿지 않는다. */
    private Optional<String> findBusinessFailure(ProductSnapshotReplyResult result) {
        if(result.items() == null || result.items().isEmpty()){
            return Optional.of("EMPTY_PRODUCT_SNAPSHOT");
        }
        if(result.requestItem() == null || result.requestItem().isEmpty()){
            return Optional.of("EMPTY_ORDER_ITEMS");
        }
        if(result.userId() == null){
            return Optional.of("MISSING_USER");
        }

        List<ProductSnapshotReplyResult.Item> requested = result.requestItem();
        List<String> blank = requested.stream()
                .map(ProductSnapshotReplyResult.Item::sku)
                .filter(sku -> sku == null || sku.isBlank())
                .toList();
        if(!blank.isEmpty()){
            return Optional.of("INVALID_SKU");
        }

        List<String> duplicated = requested.stream()
                .collect(Collectors.groupingBy(ProductSnapshotReplyResult.Item::sku, Collectors.counting()))
                .entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if(!duplicated.isEmpty()){
            return Optional.of("DUPLICATE_SKU=" + duplicated);
        }

        List<String> invalidQuantity = requested.stream()
                .filter(item -> item.quantity() == null || item.quantity() <= 0)
                .map(ProductSnapshotReplyResult.Item::sku)
                .toList();
        if(!invalidQuantity.isEmpty()){
            return Optional.of("INVALID_QUANTITY=" + invalidQuantity);
        }

        Map<String, ProductSnapshotItem> bySku = result.items().stream()
                .collect(Collectors.toMap(ProductSnapshotItem::sku, it -> it, (first, second) -> first));
        List<String> missing = requested.stream()
                .map(ProductSnapshotReplyResult.Item::sku)
                .filter(sku -> !bySku.containsKey(sku))
                .toList();
        if(!missing.isEmpty()){
            return Optional.of("MISSING_SKU=" + missing);
        }

        List<String> missingPrice = requested.stream()
                .map(ProductSnapshotReplyResult.Item::sku)
                .filter(sku -> bySku.get(sku).price() == null)
                .toList();
        if(!missingPrice.isEmpty()){
            return Optional.of("MISSING_PRICE=" + missingPrice);
        }
        return Optional.empty();
    }

    @Transactional
    public void failOrder(String idemKey, String reason) {
        idempotencyService.fail(idemKey, reason);
    }

    private String generateOrderNo() {
        // 인스턴스가 여러 개여도 겹치지 않도록 난수를 붙인다 (order_no 최대 32자)
        return "O" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }


}
