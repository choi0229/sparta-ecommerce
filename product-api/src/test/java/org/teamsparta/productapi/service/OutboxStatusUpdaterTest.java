package org.teamsparta.productapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.productapi.domain.product.entity.OutboxEvent;
import org.teamsparta.productapi.domain.product.repository.OutboxEventRepository;
import org.teamsparta.productapi.domain.product.scheduler.OutboxStatusUpdater;
import org.teamsparta.productapi.global.enums.OutboxStatus;
import org.teamsparta.productapi.global.exception.DomainException;
import org.teamsparta.productapi.global.exception.DomainExceptionCode;

import java.time.Duration;
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

    @InjectMocks
    OutboxStatusUpdater outboxStatusUpdater;

    @Mock
    OutboxEventRepository outboxEventRepository;

    private OutboxEvent pendingEvent() {
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "product-variant-event", "{}");
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
    void markFailed_atMaxRetry_becomesFailed() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        // 4회 사전 실패 처리
        for (int i = 0; i < 4; i++) {
            event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        }

        outboxStatusUpdater.markFailed(1L);

        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextRetryAt()).isNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }
}
