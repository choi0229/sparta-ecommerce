package org.teamsparta.orderapi.global.enums;

public enum SagaState {
    STARTED,
    INVENTORY_RESERVE_REQUESTED,
    INVENTORY_RESERVED,
    FAILED,
    PAYMENT_REQUESTED,
    COMPLETED,
    PAYMENT_COMPLETED,
    EXPIRED
}
