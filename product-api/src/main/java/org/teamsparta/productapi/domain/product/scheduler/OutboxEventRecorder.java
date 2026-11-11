package org.teamsparta.productapi.domain.product.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;

import java.util.List;

/**
 * Outbox 행 저장의 단일 진입점.
 *
 * <p>호출부 트랜잭션 안에서 행을 저장하고(도메인 상태 변경과 같은 트랜잭션), 저장한 행 ID를 담은
 * {@link OutboxEventRecorded}를 발행한다. 이 이벤트는 커밋된 뒤에만 OutboxImmediatePublisher가 받는다.
 * 롤백되면 이벤트도 버려지고, 행도 남지 않는다.
 */
@Component
@RequiredArgsConstructor
public class OutboxEventRecorder {

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    public OutboxEvent save(OutboxEvent event) {
        OutboxEvent saved = outboxEventRepository.save(event);
        applicationEventPublisher.publishEvent(new OutboxEventRecorded(saved.getId()));
        return saved;
    }

    public List<OutboxEvent> saveAll(List<OutboxEvent> events) {
        List<OutboxEvent> saved = outboxEventRepository.saveAll(events);
        saved.forEach(e -> applicationEventPublisher.publishEvent(new OutboxEventRecorded(e.getId())));
        return saved;
    }
}
