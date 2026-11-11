package org.teamsparta.orderapi.domain.order.scheduler;

/**
 * Outbox 행이 저장되었음을 알리는 애플리케이션 이벤트. 저장한 트랜잭션이 커밋된 뒤에만 즉시 발행 대상이 된다.
 *
 * @param outboxEventId 저장된 outbox_event.id
 */
public record OutboxEventRecorded(Long outboxEventId) {
}
