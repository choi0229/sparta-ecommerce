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
import org.teamsparta.orderapi.global.enums.SagaState;

import java.time.ZonedDateTime;
import java.util.UUID;

@Table(name = "order_saga_state")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSagaState {
    @Id
    @Column(name = "saga_id", nullable = false)
    UUID sagaId;

    @Column(name = "order_id", nullable = false, unique = true)
    Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false)
    SagaState state;

    @Column(name = "last_error")
    String lastError;

    @Column(name = "reservation_id")
    UUID reservationId;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    public static OrderSagaState start(UUID saga_id, Long order_id) {
        OrderSagaState orderSagaState = new OrderSagaState();
        orderSagaState.sagaId = saga_id;
        orderSagaState.orderId = order_id;
        orderSagaState.state = SagaState.INVENTORY_RESERVE_REQUESTED;
        orderSagaState.lastError = null;
        return orderSagaState;
    }

    public void updateState(SagaState state, String lastError, UUID reservationId) {
        this.state = state;
        this.lastError = lastError;
        this.reservationId = reservationId;
    }
}
