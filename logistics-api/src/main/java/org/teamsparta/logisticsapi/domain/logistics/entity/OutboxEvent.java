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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;

import java.time.Duration;
import java.time.ZonedDateTime;

@Table(name = "outbox_event")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "aggregate_type", nullable = false)
    String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    String aggregateId;

    @Column(name = "event_type", nullable = false)
    String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    OutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    Integer retryCount;

    @Column(name = "next_retry_at")
    ZonedDateTime nextRetryAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    ZonedDateTime createdAt;

    @Column(name = "sent_at")
    ZonedDateTime sentAt;

    public static OutboxEvent pending(String aggregateType, String aggregateId,
                                      String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.payload = payload;
        event.status = OutboxStatus.PENDING;
        event.retryCount = 0;
        return event;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = ZonedDateTime.now();
        this.nextRetryAt = null;
    }

    public void markFailedAndScheduleRetry(int maxRetry, Duration baseBackoff) {
        this.retryCount++;
        if (this.retryCount >= maxRetry) {
            this.status = OutboxStatus.FAILED;
            this.nextRetryAt = null;
        } else {
            this.status = OutboxStatus.PENDING;
            long waitMultiplier = (long) Math.pow(2, this.retryCount);
            this.nextRetryAt = ZonedDateTime.now().plus(baseBackoff.multipliedBy(waitMultiplier));
        }
    }

    public boolean isFailed() {
        return OutboxStatus.FAILED.equals(this.status);
    }

    // FAILED 상태인 이벤트를 수동으로 재처리 큐에 넣는다.
    // retry_count는 유지하여 누적 실패 횟수를 보존한다.
    public void resetForRetry(ZonedDateTime now) {
        this.status = OutboxStatus.PENDING;
        this.nextRetryAt = now;
    }
}
