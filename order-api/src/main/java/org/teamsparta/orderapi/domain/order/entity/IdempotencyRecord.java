package org.teamsparta.orderapi.domain.order.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.domain.Persistable;
import org.teamsparta.orderapi.global.enums.IdempotencyStatus;

import java.time.ZonedDateTime;

@Table(name = "idempotency_request")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord implements Persistable<String> {
    @Id
    @Column(name = "idem_key", length = 128)
    String idemKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    String requestHash;

    @Column(name = "order_id")
    Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    IdempotencyStatus status;

    @Column(name = "failure_reason", length = 512)
    String failureReason;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    /** ID를 직접 지정하므로, 표시하지 않으면 save()가 merge로 동작해 기존 기록을 덮어쓴다. */
    @Transient
    boolean isNew = true;

    @Override
    public String getId() {
        return idemKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public static IdempotencyRecord start(String idemKey, String requestHash){
        IdempotencyRecord idempotencyRecord = new IdempotencyRecord();
        idempotencyRecord.idemKey = idemKey;
        idempotencyRecord.requestHash = requestHash;
        idempotencyRecord.status = IdempotencyStatus.PENDING;
        idempotencyRecord.createdAt = ZonedDateTime.now();
        idempotencyRecord.updatedAt = ZonedDateTime.now();
        return idempotencyRecord;
    }

    public void complete(Long orderId) {
        this.orderId = orderId;
        this.status = IdempotencyStatus.COMPLETED;
        this.updatedAt = ZonedDateTime.now();
    }

    public void fail(String reason) {
        this.status = IdempotencyStatus.FAILED;
        this.failureReason = truncate(reason, 512);
        this.updatedAt = ZonedDateTime.now();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
