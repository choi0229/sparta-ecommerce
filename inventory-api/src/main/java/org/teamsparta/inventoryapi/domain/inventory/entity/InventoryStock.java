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

    /** 예약분을 확정한다. 실제 재고에서 빠지므로 total과 reserved가 함께 줄어든다. */
    public void confirm(Integer quantity) {
        validateReservedDecrease(quantity);
        validateInvariant(totalQuantity - quantity, reservedQuantity - quantity);
        this.totalQuantity -= quantity;
        this.reservedQuantity -= quantity;
    }

    /** 예약분을 해제한다. 실제 재고는 그대로이므로 reserved만 줄어든다. */
    public void release(Integer quantity) {
        validateReservedDecrease(quantity);
        validateInvariant(totalQuantity, reservedQuantity - quantity);
        this.reservedQuantity -= quantity;
    }

    private void validateReservedDecrease(Integer quantity) {
        if (quantity == null || quantity <= 0 || reservedQuantity < quantity) {
            throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
        }
    }

    private void validateInvariant(int totalAfter, int reservedAfter) {
        if (totalAfter < reservedAfter || reservedAfter < 0) {
            throw new DomainException(DomainExceptionCode.INVALID_QUANTITY);
        }
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
