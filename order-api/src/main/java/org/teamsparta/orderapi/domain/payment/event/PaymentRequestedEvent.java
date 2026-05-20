package org.teamsparta.orderapi.domain.payment.event;

import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class PaymentRequestedEvent {
    private UUID eventId;
    private String eventType;
    private Long orderId;
    private UUID sagaId;
    private Long userId;
    private BigDecimal amount;

    public static PaymentRequestedEvent from(Long orderId, UUID sagaId, Long userId, BigDecimal amount) {
        PaymentRequestedEvent event = new PaymentRequestedEvent();
        event.setEventId(UUID.randomUUID());
        event.setEventType("payment.requested");
        event.setOrderId(orderId);
        event.setSagaId(sagaId);
        event.setUserId(userId);
        event.setAmount(amount);
        return event;
    }
}
