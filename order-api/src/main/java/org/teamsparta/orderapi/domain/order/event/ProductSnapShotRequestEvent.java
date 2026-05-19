package org.teamsparta.orderapi.domain.order.event;

import lombok.Data;
import org.teamsparta.orderapi.domain.order.dto.request.CreateOrderRequest;
import java.util.List;
import java.util.UUID;

@Data
public class ProductSnapShotRequestEvent {
    private UUID requestId;
    private String eventType;
    private List<CreateOrderRequest.Item> items;
    private String idemKey;
    private Long userId;
    private CreateOrderRequest.ShippingAddress shippingAddress;

    public static ProductSnapShotRequestEvent from(UUID requestId, List<CreateOrderRequest.Item> items, String idemKey, Long userId, CreateOrderRequest.ShippingAddress shippingAddress) {
        ProductSnapShotRequestEvent event = new ProductSnapShotRequestEvent();
        event.setRequestId(requestId);
        event.setEventType("productSnapshot.requested");
        event.setItems(items);
        event.setIdemKey(idemKey);
        event.setUserId(userId);
        event.setShippingAddress(shippingAddress);
        return event;
    }
}
