package org.teamsparta.orderapi.domain.payment.event;

import java.util.UUID;

public record PaymentSucceededEvent(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId
) {
    public static PaymentSucceededEvent from(PaymentRequestedEvent paymentRequestedEvent) {
        return new PaymentSucceededEvent(UUID.randomUUID(), "payment.succeeded", paymentRequestedEvent.getOrderId(), paymentRequestedEvent.getSagaId());
    }
}
