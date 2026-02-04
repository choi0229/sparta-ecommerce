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
public class IdempotencyRecord {
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

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    ZonedDateTime updatedAt;

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
}
