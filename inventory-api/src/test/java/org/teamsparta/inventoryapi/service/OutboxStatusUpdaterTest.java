package org.teamsparta.inventoryapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxStatusUpdater;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;
import org.teamsparta.inventoryapi.global.exception.DomainException;
import org.teamsparta.inventoryapi.global.exception.DomainExceptionCode;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OutboxStatusUpdaterTest {

    @Mock
    OutboxEventRepository outboxEventRepository;

    @InjectMocks
    OutboxStatusUpdater outboxStatusUpdater;

    private OutboxEvent pendingEvent() {
        OutboxEvent event = OutboxEvent.pending("Inventory", "1", "inventory-created-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    @Test
    @DisplayName("markSent() — 이벤트를 SENT 상태로 변경하고 저장한다")
    void markSent_setsStatusToSentAndSaves() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        outboxStatusUpdater.markSent(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("markFailed() — retryCount를 증가시키고 저장한다")
    void markFailed_incrementsRetryCountAndSaves() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        outboxStatusUpdater.markFailed(1L);

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextRetryAt()).isNotNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("markSent() — 존재하지 않는 id면 DomainException(EVENT_NOT_FOUND)이 발생한다")
    void markSent_notFound_throwsDomainException() {
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> outboxStatusUpdater.markSent(999L))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name()));

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markFailed() — 존재하지 않는 id면 DomainException(EVENT_NOT_FOUND)이 발생한다")
    void markFailed_notFound_throwsDomainException() {
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> outboxStatusUpdater.markFailed(999L))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name()));

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markFailed() — 5회 실패 시 FAILED 상태로 전이한다")
    void markFailed_atMaxRetry_becomeFailed() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        for (int i = 0; i < 4; i++) {
            event.markFailedAndScheduleRetry(5, java.time.Duration.ofMinutes(1));
        }

        outboxStatusUpdater.markFailed(1L);

        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextRetryAt()).isNull();
    }
}
