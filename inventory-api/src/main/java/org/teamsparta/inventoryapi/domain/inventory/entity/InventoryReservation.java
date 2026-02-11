package org.teamsparta.inventoryapi.domain.inventory.entity;

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
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;

import java.time.ZonedDateTime;
import java.util.UUID;

@Table(name = "inventory_reservation")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryReservation {
    @Id
    UUID id;

    @Column(name = "order_id", nullable = false)
    Long orderId;

    @Column(name = "saga_id", nullable = false)
    UUID sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    ReservationStatus status;

    @Column(name = "expires_at", nullable = false)
    private ZonedDateTime expiresAt;

    @Column(name = "created_at",  nullable = false)
    @CreationTimestamp
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private ZonedDateTime updatedAt;

    public static InventoryReservation create(Long orderId, UUID sagaId, ReservationStatus status, ZonedDateTime expiresAt) {
        InventoryReservation inventoryReservation = new InventoryReservation();
        inventoryReservation.orderId = orderId;
        inventoryReservation.sagaId = sagaId;
        inventoryReservation.status = status;
        inventoryReservation.expiresAt = expiresAt;
        return inventoryReservation;
    }

    public void updateStatus(ReservationStatus status) {
        this.status = status;
    }
}
