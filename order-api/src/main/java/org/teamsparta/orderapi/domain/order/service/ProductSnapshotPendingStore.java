package org.teamsparta.orderapi.domain.order.service;

import org.springframework.stereotype.Component;
import org.teamsparta.orderapi.domain.order.event.dto.ProductSnapshotReplyResult;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProductSnapshotPendingStore {
    private final ConcurrentHashMap<UUID, CompletableFuture<ProductSnapshotReplyResult>> pending = new ConcurrentHashMap<>();

    public CompletableFuture<ProductSnapshotReplyResult> register(UUID requestId){
        CompletableFuture<ProductSnapshotReplyResult> future = new CompletableFuture<>();
        pending.put(requestId, future);
        return future;
    }

    public void complete(ProductSnapshotReplyResult event){
        CompletableFuture<ProductSnapshotReplyResult> future = pending.remove(event.requestId());
        if(future!=null){
            future.complete(event);
        }
    }

    public void timeout(UUID requestId) {
        CompletableFuture<ProductSnapshotReplyResult> f = pending.remove(requestId);
        if (f != null) f.completeExceptionally(new DomainException(DomainExceptionCode.SNAPSHOT_TIMEOUT));
    }
}
