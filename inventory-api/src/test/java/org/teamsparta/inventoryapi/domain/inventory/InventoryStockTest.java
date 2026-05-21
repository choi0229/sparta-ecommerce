package org.teamsparta.inventoryapi.domain.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryStockTest {

    @Test
    @DisplayName("decreaseReserved() — reservedQuantity가 충분하면 정상 감소한다")
    void decreaseReserved_success() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(5);

        stock.decreaseReserved(5);

        assertThat(stock.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("decreaseReserved() — quantity > reservedQuantity 이면 DomainException(INVALID_QUANTITY) 발생")
    void decreaseReserved_exceedsReserved_throwsInvalidQuantity() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(3);

        assertThatThrownBy(() -> stock.decreaseReserved(5))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.INVALID_QUANTITY.name()));
    }

    @Test
    @DisplayName("decreaseReserved() — available=0이어도 reservedQuantity가 충분하면 정상 감소한다")
    void decreaseReserved_whenAvailableIsZero_success() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(10); // reserved=10, available=0

        stock.decreaseReserved(10); // 구 코드에서는 OUT_OF_STOCK이 발생하던 케이스

        assertThat(stock.getReservedQuantity()).isEqualTo(0);
    }
}
