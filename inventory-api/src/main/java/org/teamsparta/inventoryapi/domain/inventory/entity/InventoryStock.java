package org.teamsparta.inventoryapi.domain.inventory.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

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

    public void increaseReserved(Integer quantity) {
        if (totalQuantity - reservedQuantity < quantity) {
            throw new DomainException(DomainExceptionCode.OUT_OF_STOCK);
        }
        this.reservedQuantity += quantity;
    }

    public void decreaseReserved(Integer quantity) {
        if (this.reservedQuantity < quantity) {
            throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
        }
        this.reservedQuantity -= quantity;
    }

    @Builder
    public static InventoryStock create(String sku, Integer totalQuantity) {
        return new InventoryStock(
                sku,
                totalQuantity,
                0,
                null,
                null
        );
    }
}
