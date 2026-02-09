package org.teamsparta.inventoryapi.global.enums;

public enum ReservationStatus {
    RESERVED,   // 재고 점유 중
    CONFIRMED,  // 결제 완료로 인한 확정
    CANCELED,   // 취소됨
    EXPIRED     // 시간 만료
}
