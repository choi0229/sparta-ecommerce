package org.teamsparta.orderapi.domain.payment.event;

import java.util.UUID;

public record PaymentFailedEvent(
        UUID eventId,
        String eventType,
        Long orderId,
        UUID sagaId,
        String reason
) {
    public static PaymentFailedEvent from(PaymentRequestedEvent paymentRequestedEvent, String reason) {
        return new PaymentFailedEvent(UUID.randomUUID(), "payment.failed", paymentRequestedEvent.getOrderId(), paymentRequestedEvent.getSagaId(), reason);
    }
}
