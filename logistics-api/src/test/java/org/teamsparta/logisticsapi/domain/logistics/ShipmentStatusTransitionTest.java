package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.teamsparta.logisticsapi.global.enums.ShipmentStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;

import static org.assertj.core.api.Assertions.*;

class ShipmentStatusTransitionTest {

    @Test
    @DisplayName("READY에서 SHIPPED로 전이할 수 있다")
    void ready_to_shipped() {
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.READY.validateTransitionTo(ShipmentStatus.SHIPPED));
    }

    @Test
    @DisplayName("READY에서 CANCELED로 전이할 수 있다")
    void ready_to_canceled() {
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.READY.validateTransitionTo(ShipmentStatus.CANCELED));
    }

    @Test
    @DisplayName("SHIPPED에서 IN_TRANSIT, FAILED, CANCELED로 전이할 수 있다")
    void shipped_allowed_transitions() {
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.SHIPPED.validateTransitionTo(ShipmentStatus.IN_TRANSIT));
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.SHIPPED.validateTransitionTo(ShipmentStatus.FAILED));
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.SHIPPED.validateTransitionTo(ShipmentStatus.CANCELED));
    }

    @Test
    @DisplayName("IN_TRANSIT에서 DELIVERED, FAILED로 전이할 수 있다")
    void in_transit_allowed_transitions() {
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.IN_TRANSIT.validateTransitionTo(ShipmentStatus.DELIVERED));
        assertThatNoException().isThrownBy(() ->
                ShipmentStatus.IN_TRANSIT.validateTransitionTo(ShipmentStatus.FAILED));
    }

    @Test
    @DisplayName("DELIVERED 이후에는 어떤 상태로도 전이할 수 없다")
    void delivered_cannot_transition() {
        for (ShipmentStatus next : ShipmentStatus.values()) {
            assertThatThrownBy(() -> ShipmentStatus.DELIVERED.validateTransitionTo(next))
                    .isInstanceOf(DomainException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(value = ShipmentStatus.class, names = {"FAILED", "CANCELED"})
    @DisplayName("FAILED, CANCELED 이후에는 어떤 상태로도 전이할 수 없다")
    void terminal_states_cannot_transition(ShipmentStatus terminalStatus) {
        for (ShipmentStatus next : ShipmentStatus.values()) {
            assertThatThrownBy(() -> terminalStatus.validateTransitionTo(next))
                    .isInstanceOf(DomainException.class);
        }
    }

    @Test
    @DisplayName("READY에서 IN_TRANSIT으로 직접 전이하면 예외가 발생한다")
    void ready_to_in_transit_throws() {
        assertThatThrownBy(() -> ShipmentStatus.READY.validateTransitionTo(ShipmentStatus.IN_TRANSIT))
                .isInstanceOf(DomainException.class);
    }
}
