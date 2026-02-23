package org.teamsparta.orderapi.domain.order.event;

import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ProductSnapShotRequestEvent {
    private UUID requestId;
    private String eventType;
    private List<String> skus;

    public static ProductSnapShotRequestEvent from(UUID requestId, List<String> skus){
        ProductSnapShotRequestEvent event = new ProductSnapShotRequestEvent();
        event.setRequestId(requestId);
        event.setEventType("productSnapshot.requested");
        event.setSkus(skus);
        return event;
    }
}
