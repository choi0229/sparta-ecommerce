package org.teamsparta.inventoryapi.domain.inventory.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;

@Table(name = "inventory_stock")
@Entity
@Getter
@DynamicInsert
@DynamicUpdate
@FieldDefaults(level = AccessLevel.PRIVATE)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryStock {
    @Id
    String sku;

    @Column(name = "total_quantity", nullable = false)
    Integer totalQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    Integer reservedQuantity;

    @Column(name = "version")
    @Version
    Long version;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    ZonedDateTime updatedAt;
}
