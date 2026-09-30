package org.teamsparta.orderapi.domain.order.event;

/**
 * 주문 조회 상태가 바뀌었음을 알리는 애플리케이션 내부 이벤트 (Kafka 이벤트 아님).
 * 주문 상태 캐시 무효화에 사용한다.
 */
public record OrderStatusChangedEvent(Long orderId) {
}
