package org.teamsparta.orderapi.domain.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.event.dto.*;
import org.teamsparta.orderapi.domain.order.service.OrderSagaService;
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

    @KafkaListener(topics = "inventory-reserved-event", groupId = "${spring.application.name}")
    public void InventoryReservedEvent(String message){
        log.info("Received inventory-reserved-event message: {}", message);
        try{
            InventoryReservedResult inventoryReservedResult = objectMapper.readValue(message, InventoryReservedResult.class);
            orderSagaService.onInventoryReserved(inventoryReservedResult);
        }catch(Exception e){
            log.error("Error parsing inventory-reserved-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "inventory-failed-event", groupId = "${spring.application.name}")
    public void InventoryReserveFailedEvent(String message){
        log.info("Received inventory-failed-event message: {}", message);
        try{
            InventoryReserveFailedResult inventoryReservedResult = objectMapper.readValue(message, InventoryReserveFailedResult.class);
            orderSagaService.onInventoryReserveFailed(inventoryReservedResult);
        }catch(Exception e){
            log.error("Error parsing inventory-failed-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "payment-succeeded-event", groupId = "${spring.application.name}")
    public void PaymentRequestSucceededEvent(String message){
        log.info("Received payment-succeeded-event message: {}", message);
        try{
            PaymentSucceededEvent paymentSucceededEvent = objectMapper.readValue(message, PaymentSucceededEvent.class);
            orderSagaService.onPaymentSucceeded(paymentSucceededEvent);
        }catch(Exception e){
            log.error("Error parsing payment-succeeded-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "payment-failed-event", groupId = "${spring.application.name}")
    public void PaymentRequestFailedEvent(String message){
        log.info("Received payment-failed-event message: {}", message);
        try{
            PaymentFailedEvent paymentFailedEvent = objectMapper.readValue(message, PaymentFailedEvent.class);
            orderSagaService.onPaymentFailed(paymentFailedEvent);
        }catch(Exception e){
            log.error("Error parsing payment-succeeded-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "inventory-confirm-event", groupId = "${spring.application.name}")
    public void InventoryConfirmedEvent(String message){
        log.info("Received inventory-confirm-event message: {}", message);
        try{
            InventoryConfirmedResult inventoryConfirmedResult = objectMapper.readValue(message, InventoryConfirmedResult.class);
            orderSagaService.onInventoryConfirmed(inventoryConfirmedResult);
        }catch(Exception e){
            log.error("Error parsing inventory-confirm-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "inventory-expired-event", groupId = "${spring.application.name}")
    public void InventoryExpiredEvent(String message){
        log.info("Received inventory-expired-event message: {}", message);
        try{
            InventoryReservationExpiredResult inventoryExpiredResult = objectMapper.readValue(message, InventoryReservationExpiredResult.class);
            orderSagaService.onInventoryExpired(inventoryExpiredResult);
        }catch(Exception e){
            log.error("Error parsing inventory-expired-event message: {}", message, e);
        }
    }

    @KafkaListener(topics = "productSnapshot-reply-event", groupId = "order-api")
    public void ProductSnapshotReplyEvent(String message){
        log.info("Received product-snapshot-reply-event message: {}", message);
        try{
            ProductSnapshotReplyResult productSnapshotReplyResult = objectMapper.readValue(message, ProductSnapshotReplyResult.class);
            productSnapshotPendingStore.complete(productSnapshotReplyResult);
        }catch(Exception e){
            log.error("Error parsing product-snapshot-reply-event message: {}", message, e);
        }
    }
}