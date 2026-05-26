package org.teamsparta.logisticsapi.global.enums;

import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.util.Set;

public enum ShipmentStatus {

    READY {
        @Override
        public Set<ShipmentStatus> allowedTransitions() {
            return Set.of(SHIPPED, CANCELED);
        }
    },
    SHIPPED {
        @Override
        public Set<ShipmentStatus> allowedTransitions() {
            return Set.of(IN_TRANSIT, FAILED, CANCELED);
        }
    },
    IN_TRANSIT {
        @Override
        public Set<ShipmentStatus> allowedTransitions() {
            return Set.of(DELIVERED, FAILED);
        }
    },
    DELIVERED {
        @Override
        public Set<ShipmentStatus> allowedTransitions() {
            return Set.of();
        }
    },
    FAILED {
        @Override
        public Set<ShipmentStatus> allowedTransitions() {
            return Set.of();
        }
    },
    CANCELED {
        @Override
        public Set<ShipmentStatus> allowedTransitions() {
            return Set.of();
        }
    };

    public abstract Set<ShipmentStatus> allowedTransitions();

    public void validateTransitionTo(ShipmentStatus next) {
        if (!allowedTransitions().contains(next)) {
            throw new DomainException(DomainExceptionCode.INVALID_SHIPMENT_STATUS_TRANSITION);
        }
    }
}
