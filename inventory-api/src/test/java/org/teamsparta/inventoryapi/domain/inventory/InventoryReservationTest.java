package org.teamsparta.inventoryapi.domain.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsparta.inventoryapi.domain.inventory.entity.InventoryReservation;
import org.teamsparta.inventoryapi.global.enums.ReservationStatus;

import java.time.ZonedDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryReservationTest {

    @Test
    @DisplayName("create()는 UUID id를 할당한다")
    void create_assignsUuid() {
        InventoryReservation reservation = InventoryReservation.create(
                1L, UUID.randomUUID(), ReservationStatus.RESERVED, ZonedDateTime.now().plusMinutes(30)
        );

        assertThat(reservation.getId()).isNotNull();
    }
}
