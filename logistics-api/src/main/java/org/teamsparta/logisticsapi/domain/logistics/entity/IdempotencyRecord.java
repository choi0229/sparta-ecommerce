package org.teamsparta.logisticsapi.domain.logistics.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.teamsparta.logisticsapi.global.enums.IdempotencyStatus;

import java.time.ZonedDateTime;

@Table(name = "idempotency_record")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    IdempotencyStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    ZonedDateTime createdAt;

    @Column(name = "processed_at")
    ZonedDateTime processedAt;

    public static IdempotencyRecord start(String idemKey) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.idemKey = idemKey;
        record.status = IdempotencyStatus.PENDING;
        return record;
    }

    public void complete() {
        this.status = IdempotencyStatus.COMPLETED;
        this.processedAt = ZonedDateTime.now();
    }

    public boolean isAlreadyProcessed() {
        return this.status == IdempotencyStatus.COMPLETED;
    }
}
