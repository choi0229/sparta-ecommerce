package org.teamsparta.orderapi.domain.order.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.event.dto.*;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
import org.teamsparta.orderapi.domain.order.service.OrderTransactionalService;
import org.teamsparta.orderapi.domain.order.service.ProductSnapshotPendingStore;
import org.teamsparta.orderapi.domain.payment.event.PaymentFailedEvent;
import org.teamsparta.orderapi.domain.payment.event.PaymentSucceededEvent;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderSagaService orderSagaService;
    private final ProductSnapshotPendingStore productSnapshotPendingStore;
    private final OrderTransactionalService orderTransactionalService;

    // 예외를 잡지 않는다. JSON 오류와 시스템 예외는 KafkaConfig의 DefaultErrorHandler가 처리한다.

    @KafkaListener(topics = "inventory-reserved-event", groupId = "${spring.application.name}")
    public void InventoryReservedEvent(String message) throws JsonProcessingException {
        log.info("Received inventory-reserved-event message: {}", message);
        orderSagaService.onInventoryReserved(objectMapper.readValue(message, InventoryReservedResult.class));
    }

    @KafkaListener(topics = "inventory-failed-event", groupId = "${spring.application.name}")
    public void InventoryReserveFailedEvent(String message) throws JsonProcessingException {
        log.info("Received inventory-failed-event message: {}", message);
        orderSagaService.onInventoryReserveFailed(objectMapper.readValue(message, InventoryReserveFailedResult.class));
    }

    @KafkaListener(topics = "payment-succeeded-event", groupId = "${spring.application.name}")
    public void PaymentRequestSucceededEvent(String message) throws JsonProcessingException {
        log.info("Received payment-succeeded-event message: {}", message);
        orderSagaService.onPaymentSucceeded(objectMapper.readValue(message, PaymentSucceededEvent.class));
    }

    @KafkaListener(topics = "payment-failed-event", groupId = "${spring.application.name}")
    public void PaymentRequestFailedEvent(String message) throws JsonProcessingException {
        log.info("Received payment-failed-event message: {}", message);
        orderSagaService.onPaymentFailed(objectMapper.readValue(message, PaymentFailedEvent.class));
    }

    @KafkaListener(topics = "inventory-confirm-event", groupId = "${spring.application.name}")
    public void InventoryConfirmedEvent(String message) throws JsonProcessingException {
        log.info("Received inventory-confirm-event message: {}", message);
        orderSagaService.onInventoryConfirmed(objectMapper.readValue(message, InventoryConfirmedResult.class));
    }

    @KafkaListener(topics = "inventory-confirm-failed-event", groupId = "${spring.application.name}")
    public void InventoryConfirmFailedEvent(String message) throws JsonProcessingException {
        log.info("Received inventory-confirm-failed-event message: {}", message);
        orderSagaService.onInventoryConfirmFailed(objectMapper.readValue(message, InventoryConfirmFailedResult.class));
    }

    @KafkaListener(topics = "inventory-expired-event", groupId = "${spring.application.name}")
    public void InventoryExpiredEvent(String message) throws JsonProcessingException {
        log.info("Received inventory-expired-event message: {}", message);
        orderSagaService.onInventoryExpired(objectMapper.readValue(message, InventoryReservationExpiredResult.class));
    }

    @KafkaListener(topics = "productSnapshot-reply-event", groupId = "order-api")
    public void ProductSnapshotReplyEvent(String message) throws JsonProcessingException {
        log.info("Received product-snapshot-reply-event message: {}", message);
        ProductSnapshotReplyResult result = objectMapper.readValue(message, ProductSnapshotReplyResult.class);
        if (!result.success() || result.error() != null) {
            String reason = result.error() != null ? result.error() : "Product snapshot failed";
            log.warn("Product snapshot failed. idemKey={}, reason={}", result.idemKey(), reason);
            orderTransactionalService.failOrder(result.idemKey(), reason);
            return;
        }
        orderTransactionalService.createOrderInternal(result, result.idemKey());
    }
}
