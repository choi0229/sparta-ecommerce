package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventTransactionalServiceTest {

    @Mock OutboxEventRepository outboxEventRepository;

    @InjectMocks
    OutboxEventTransactionalService service;

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending("shipment", "1", "shipment-created-event", "{}");
    }

    @Test
    @DisplayName("markSent() — 이벤트를 조회하고 SENT 상태로 저장한다")
    void markSent_fetchesEventAndSaves() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        service.markSent(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("markFailed() — 이벤트를 조회하고 retryCount를 증가시켜 저장한다")
    void markFailed_fetchesEventAndSchedulesRetry() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        service.markFailed(1L);

        assertThat(event.getRetryCount()).isEqualTo(1);
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("markSent() — 존재하지 않는 id면 DomainException(EVENT_NOT_FOUND)이 발생한다")
    void markSent_notFound_throwsDomainException() {
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.markSent(999L))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name()));

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markFailed() — 존재하지 않는 id면 DomainException(EVENT_NOT_FOUND)이 발생한다")
    void markFailed_notFound_throwsDomainException() {
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.markFailed(999L))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name()));

        then(outboxEventRepository).should(never()).save(any());
    }
}
