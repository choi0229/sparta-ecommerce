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
import org.teamsparta.orderapi.global.enums.ShipmentStatus;
import org.teamsparta.orderapi.global.enums.Status;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

@Table(name = "orders")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Orders {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    String orderNo;

    @Column(name = "user_id", nullable = false)
    Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    Status status;

    @Column(name = "total_amount", nullable = false)
    BigDecimal totalAmount;

    @Column(name = "discount_amount", nullable = false)
    BigDecimal discountAmount;

    @Column(name = "pay_amount", nullable = false)
    BigDecimal payAmount;

    @Column(name = "failure_reason")
    String failureReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_status")
    ShipmentStatus shipmentStatus;

    @Column(name = "saga_id", nullable = false, unique = true)
    UUID sagaId;

    @Column(name = "created_at", updatable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;

    @Column(name = "updated_at")
    @UpdateTimestamp
    ZonedDateTime updatedAt;

    public static Orders createNew(String orderNo, Long userId) {
        Orders order = new Orders();
        order.orderNo = orderNo;
        order.userId = userId;
        order.status = Status.CREATED;
        order.totalAmount = BigDecimal.ZERO;
        order.discountAmount = BigDecimal.ZERO;
        order.payAmount = BigDecimal.ZERO;
        order.failureReason = null;
        order.sagaId = UUID.randomUUID();
        return order;
    }

    public void updateStatus(Status status) {
        this.status = status;
    }

    public void updateShipmentStatus(ShipmentStatus shipmentStatus) {
        this.shipmentStatus = shipmentStatus;
    }

}
