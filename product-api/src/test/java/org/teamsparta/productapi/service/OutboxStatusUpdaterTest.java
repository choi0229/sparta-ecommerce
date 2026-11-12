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

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        OutboxEvent event = OutboxEvent.pending("Product", "SKU-1", "product-variant-event", "{}");
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    @Test
    @DisplayName("markSent() — 조회 없이 자신의 claim 시각을 조건으로 한 UPDATE 하나로 SENT 처리하고 갱신 행 수를 돌려준다")
    void markSent_updatesClaimedRowsWithoutSelect() {
        ZonedDateTime claimedAt = ZonedDateTime.now();
        given(outboxEventRepository.markSentIfClaimed(eq(List.of(1L, 2L)), eq(claimedAt), any())).willReturn(1);

        int updated = outboxStatusUpdater.markSent(List.of(1L, 2L), claimedAt);

        assertThat(updated).isEqualTo(1);
        then(outboxEventRepository).should(never()).findById(any());
        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markFailed() — 자신이 claim한 행이면 retryCount를 증가시키고 저장한다")
    void markFailed_incrementsRetryCountAndSaves() {
        OutboxEvent event = pendingEvent();
        ZonedDateTime claimedAt = ZonedDateTime.now();
        given(outboxEventRepository.findClaimedForUpdate(1L, claimedAt)).willReturn(Optional.of(event));

        assertThat(outboxStatusUpdater.markFailed(1L, claimedAt)).isTrue();

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getNextRetryAt()).isNotNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("markSent() — 빈 목록이면 쿼리하지 않는다")
    void markSent_emptyIds_noQuery() {
        assertThat(outboxStatusUpdater.markSent(List.of(), ZonedDateTime.now())).isZero();

        then(outboxEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("markFailed() — 자신의 claim이 아닌 행(복구·재claim됨)이면 아무것도 바꾸지 않고 false를 돌려준다")
    void markFailed_notClaimed_skips() {
        ZonedDateTime claimedAt = ZonedDateTime.now();
        given(outboxEventRepository.findClaimedForUpdate(999L, claimedAt)).willReturn(Optional.empty());

        assertThat(outboxStatusUpdater.markFailed(999L, claimedAt)).isFalse();

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("markFailed() — 5회 실패 시 FAILED 상태로 전이한다")
    void markFailed_atMaxRetry_becomesFailed() {
        OutboxEvent event = pendingEvent();
        ZonedDateTime claimedAt = ZonedDateTime.now();
        given(outboxEventRepository.findClaimedForUpdate(1L, claimedAt)).willReturn(Optional.of(event));

        // 4회 사전 실패 처리
        for (int i = 0; i < 4; i++) {
            event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        }

        outboxStatusUpdater.markFailed(1L, claimedAt);

        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getNextRetryAt()).isNull();
    }
}
