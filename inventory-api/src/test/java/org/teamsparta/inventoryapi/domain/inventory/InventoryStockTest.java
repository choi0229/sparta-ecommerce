package org.teamsparta.inventoryapi.domain.inventory;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryStock;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryStockTest {

    @Test
    @DisplayName("confirm() — total과 reserved가 함께 감소한다")
    void confirm_decreasesTotalAndReserved() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(5);

        stock.confirm(3);

        assertThat(stock.getTotalQuantity()).isEqualTo(7);
        assertThat(stock.getReservedQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("confirm() — available=0이어도 전량 확정되어 total=0, reserved=0이 된다")
    void confirm_whenAvailableIsZero_success() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(10); // reserved=10, available=0

        stock.confirm(10);

        assertThat(stock.getTotalQuantity()).isEqualTo(0);
        assertThat(stock.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("release() — reserved만 감소하고 total은 유지된다")
    void release_decreasesReservedOnly() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(5);

        stock.release(5);

        assertThat(stock.getTotalQuantity()).isEqualTo(10);
        assertThat(stock.getReservedQuantity()).isEqualTo(0);
    }

    @Test
    @DisplayName("release() — available=0이어도 reservedQuantity가 충분하면 정상 감소한다")
    void release_whenAvailableIsZero_success() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(10); // reserved=10, available=0

        stock.release(10); // 구 코드에서는 OUT_OF_STOCK이 발생하던 케이스

        assertThat(stock.getReservedQuantity()).isEqualTo(0);
    }

    @ParameterizedTest(name = "quantity={0}")
    @NullSource
    @ValueSource(ints = {0, -1})
    @DisplayName("confirm(), release() — quantity가 null이거나 0 이하면 INVALID_QUANTITY, 값은 유지된다")
    void nonPositiveQuantity_throwsInvalidQuantity(Integer quantity) {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(3);

        assertInvalidQuantity(() -> stock.confirm(quantity));
        assertInvalidQuantity(() -> stock.release(quantity));

        assertThat(stock.getTotalQuantity()).isEqualTo(10);
        assertThat(stock.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("confirm(), release() — quantity > reservedQuantity 이면 INVALID_QUANTITY, 값은 유지된다")
    void exceedsReserved_throwsInvalidQuantity() {
        InventoryStock stock = InventoryStock.create("SKU-001", 10);
        stock.increaseReserved(3);

        assertInvalidQuantity(() -> stock.confirm(5));
        assertInvalidQuantity(() -> stock.release(5));

        assertThat(stock.getTotalQuantity()).isEqualTo(10);
        assertThat(stock.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("변경 후 total < reserved가 되는 손상된 재고는 INVALID_QUANTITY로 거부한다")
    void corruptedStock_totalBelowReservedAfterChange_throwsInvalidQuantity() {
        InventoryStock stock = InventoryStock.create("SKU-001", 5);
        ReflectionTestUtils.setField(stock, "reservedQuantity", 7); // total=5, reserved=7

        assertInvalidQuantity(() -> stock.confirm(2));   // 변경 후 3 < 5
        assertInvalidQuantity(() -> stock.release(1));   // 변경 후 5 < 6

        assertThat(stock.getTotalQuantity()).isEqualTo(5);
        assertThat(stock.getReservedQuantity()).isEqualTo(7);
    }

    private void assertInvalidQuantity(ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.INVALID_QUANTITY.name()));
    }
}
