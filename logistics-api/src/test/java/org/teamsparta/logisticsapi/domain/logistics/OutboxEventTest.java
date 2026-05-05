package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;

import java.time.Duration;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

class OutboxEventTest {

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending("shipment", "1", "shipment-created-event", "{}");
    }

    @Test
    @DisplayName("markSent() 호출 후 SENT 상태가 되고 sentAt이 기록된다")
    void markSent_changeStatusToSent() {
        OutboxEvent event = pendingEvent();
        ZonedDateTime before = ZonedDateTime.now();

        event.markSent();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getSentAt()).isAfterOrEqualTo(before);
        assertThat(event.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("retryCount < maxRetry 이면 PENDING 유지, retryCount 증가, nextRetryAt 지수 증가")
    void markFailed_belowMaxRetry_remainsPendingWithBackoff() {
        OutboxEvent event = pendingEvent();
        ZonedDateTime before = ZonedDateTime.now();

        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt()).isNotNull();
        // 2^1 * 1분 = 2분 후. 실행 지연을 고려해 ±5초 허용
        ZonedDateTime expected = before.plusMinutes(2);
        assertThat(event.getNextRetryAt()).isCloseTo(expected, within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("retryCount >= maxRetry 이면 FAILED 전이, nextRetryAt null")
    void markFailed_atMaxRetry_becomeFailed() {
        OutboxEvent event = pendingEvent();
        // 4회 실패 상태로 만든다
        for (int i = 0; i < 4; i++) {
            event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        }

        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(5);
        assertThat(event.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("지수 백오프: 2회차 nextRetryAt은 1회차보다 더 뒤다")
    void markFailed_backoffIncreasesByRetry() {
        OutboxEvent event = pendingEvent();

        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        ZonedDateTime firstRetryAt = event.getNextRetryAt();

        event.markFailedAndScheduleRetry(5, Duration.ofMinutes(1));
        ZonedDateTime secondRetryAt = event.getNextRetryAt();

        assertThat(secondRetryAt).isAfter(firstRetryAt);
    }
}
