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

import java.time.ZonedDateTime;
import java.util.UUID;

@Table(name = "inventory_reservation_item")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryReservationItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "reservation_id", nullable = false)
    UUID reservationId;

    @Column(name = "sku", nullable = false)
    String sku;

    @Column(name = "quantity", nullable = false)
    Integer quantity;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    ZonedDateTime createdAt;
}
