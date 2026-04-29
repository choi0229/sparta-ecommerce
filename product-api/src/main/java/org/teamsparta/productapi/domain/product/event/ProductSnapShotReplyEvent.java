package org.teamsparta.productapi.domain.product.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.teamsparta.productapi.domain.product.event.dto.ProductSnapshotRequestResult;
import org.teamsparta.productapi.global.enums.Status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
public class ProductSnapShotReplyEvent {
    private UUID requestId;
    private String eventType;
    private boolean success;
    private String error;
    private List<ProductSnapshotItem> items;
    private List<ProductSnapshotRequestResult.Item> requestItem;
    private String idemKey;
    private Long userId;

    public static ProductSnapShotReplyEvent ok(UUID requestId, List<ProductSnapshotItem> items, List<ProductSnapshotRequestResult.Item> requestItem, String idemKey, Long userId){
        ProductSnapShotReplyEvent event = new ProductSnapShotReplyEvent();
        event.setRequestId(requestId);
        event.setSuccess(true);
        event.setError(null);
        event.setItems(items);
        event.setRequestItem(requestItem);
        event.setIdemKey(idemKey);
        event.setUserId(userId);
        return event;
    }

    public static ProductSnapShotReplyEvent error(UUID requestId, String error,
                                                   String idemKey, Long userId,
                                                   List<ProductSnapshotRequestResult.Item> requestItem) {
        ProductSnapShotReplyEvent event = new ProductSnapShotReplyEvent();
        event.setRequestId(requestId);
        event.setSuccess(false);
        event.setError(error);
        event.setItems(null);
        event.setIdemKey(idemKey);
        event.setUserId(userId);
        event.setRequestItem(requestItem);
        return event;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class ProductSnapshotItem {
        private String sku;
        private Long variantId;
        private String productName;
        private Long productId;
        private BigDecimal price;
        private Map<String, Object> optionJson;
    }
}
