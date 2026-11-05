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
    private final OutboxEventRepository outboxEventRepository;
    private final ProductSnapshotPendingStore productSnapshotPendingStore;
    private final KafkaTemplate<String, String> kafkaTemplate;

    /** 멱등 기록과 주문 접수 Outbox를 같은 트랜잭션에 저장한다. 같은 키가 이미 있으면 함께 롤백된다. */
    @Transactional
    public void acceptOrder(String idemKey, String requestHash, String snapshotRequestPayload) {
        idempotencyService.start(idemKey, requestHash);
        outboxEventRepository.save(OutboxEvent.pending("Orders", UUID.randomUUID().toString(), "productSnapshot-requested-event", snapshotRequestPayload));
    }

    @Transactional
    public void createOrderInternal(ProductSnapshotReplyResult result, String idemKey) {
        Map<String, ProductSnapshotItem> bySku = result.items().stream()
                .collect(Collectors.toMap(ProductSnapshotItem::sku, it -> it));
        List<String> skus = result.requestItem().stream()
                .map(ProductSnapshotReplyResult.Item::sku)
                .toList();

        IdempotencyRecord idemRecord = idempotencyService.getForUpdate(idemKey);

        if(idemRecord.getStatus() != IdempotencyStatus.PENDING){
            log.info("Duplicate product snapshot reply ignored. idemKey={}, status={}", idemKey, idemRecord.getStatus());
            return;
        }

        List<String> missing = skus.stream()
                .filter(sku -> !bySku.containsKey(sku))
                .distinct()
                .toList();
        if(!missing.isEmpty()){
            throw new DomainException(DomainExceptionCode.PRODUCT_SNAPSHOT_NOT_READY);
        }

        String orderNo = generateOrderNo();
        Orders order = Orders.createNew(orderNo, result.userId());
        if (result.shippingAddress() != null) {
            order.setShippingAddress(result.shippingAddress().recipientName(), result.shippingAddress().recipientAddress());
        }
        Orders savedOrder = orderRepository.save(order);

        OrderSagaState sagaState = OrderSagaState.start(order.getSagaId(), savedOrder.getId());
        sagaStateRepository.save(sagaState);

        BigDecimal total = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        for(ProductSnapshotReplyResult.Item item : result.requestItem()){
            if(item.quantity() == null || item.quantity() <= 0){
                throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
            }

            ProductSnapshotItem snap = bySku.get(item.sku());

            BigDecimal unitPrice = snap.price();
            BigDecimal lineAmount = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));
            total = total.add(lineAmount);

            Map<String, Object> optionJsonSnapShot = snap.optionJson();

            OrderItem orderItem = OrderItem.of(
                    savedOrder.getId(),
                    snap.sku(),
                    item.quantity(),
                    unitPrice,
                    snap.productName(),
                    null,
                    optionJsonSnapShot,
                    snap.productId().toString()
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
        outboxEventRepository.save(OutboxEvent.pending("Orders", savedOrder.getId().toString(), "order-create-event", payload));
        idempotencyService.complete(idemKey, savedOrder.getId());
    }


    public void failOrder(String idemKey, String reason) {
        idempotencyService.fail(idemKey, reason);
        log.info("Order failed due to product snapshot failure. idemKey={}, reason={}", idemKey, reason);
    }

    private String generateOrderNo() {
        return "O" + System.currentTimeMillis();
    }


}
