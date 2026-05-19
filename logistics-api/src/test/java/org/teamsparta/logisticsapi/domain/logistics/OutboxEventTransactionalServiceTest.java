package org.teamsparta.logisticsapi.domain.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.logisticsapi.domain.logistics.entity.OutboxEvent;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxEventRepository;
import org.teamsparta.logisticsapi.domain.logistics.repository.OutboxQueryRepository;
import org.teamsparta.logisticsapi.domain.logistics.service.OutboxEventTransactionalService;
import org.teamsparta.logisticsapi.global.enums.OutboxStatus;
import org.teamsparta.logisticsapi.global.exception.DomainException;
import org.teamsparta.logisticsapi.global.exception.DomainExceptionCode;

import org.springframework.data.domain.PageRequest;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxEventTransactionalServiceTest {

    @Mock OutboxEventRepository outboxEventRepository;
    @Mock OutboxQueryRepository outboxQueryRepository;

    @InjectMocks
    OutboxEventTransactionalService service;

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending("shipment", "1", "shipment-created-event", "{}");
    }

    private OutboxEvent failedEvent() {
        OutboxEvent event = OutboxEvent.pending("shipment", "1", "shipment-created-event", "{}");
        ReflectionTestUtils.setField(event, "status", OutboxStatus.FAILED);
        ReflectionTestUtils.setField(event, "retryCount", 5);
        ReflectionTestUtils.setField(event, "sentAt", ZonedDateTime.now());
        return event;
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

    @Test
    @DisplayName("markPermanentFailed() — retryCount 증가 없이 즉시 FAILED로 저장한다")
    void markPermanentFailed_directlyFailed_noRetryCountIncrease() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        service.markPermanentFailed(1L);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(0);
        assertThat(event.getNextRetryAt()).isNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("markPermanentFailed() — 존재하지 않는 id면 DomainException(EVENT_NOT_FOUND)이 발생한다")
    void markPermanentFailed_notFound_throwsDomainException() {
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.markPermanentFailed(999L))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name()));

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("retryFailed() — FAILED 이벤트를 PENDING으로 전환하고 저장한다")
    void retryFailed_failedEvent_resetsToPending() {
        OutboxEvent event = failedEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));
        given(outboxEventRepository.save(event)).willReturn(event);

        OutboxEvent result = service.retryFailed(1L, ZonedDateTime.now());

        assertThat(result.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(result.getRetryCount()).isEqualTo(5);
        assertThat(result.getNextRetryAt()).isNotNull();
        assertThat(result.getSentAt()).isNull();
        then(outboxEventRepository).should(times(1)).save(event);
    }

    @Test
    @DisplayName("retryFailed() — 존재하지 않는 id면 DomainException(EVENT_NOT_FOUND)이 발생한다")
    void retryFailed_notFound_throwsDomainException() {
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.retryFailed(999L, ZonedDateTime.now()))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.EVENT_NOT_FOUND.name()));

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("retryFailed() — FAILED가 아닌 이벤트면 DomainException(OUTBOX_EVENT_NOT_FAILED)이 발생한다")
    void retryFailed_notFailedStatus_throwsDomainException() {
        OutboxEvent event = pendingEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(event));

        assertThatThrownBy(() -> service.retryFailed(1L, ZonedDateTime.now()))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.OUTBOX_EVENT_NOT_FAILED.name()));

        then(outboxEventRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("findByStatus() — status에 해당하는 이벤트 목록을 limit만큼 반환한다")
    void findByStatus_returnsMatchingEvents() {
        OutboxEvent e1 = failedEvent();
        OutboxEvent e2 = failedEvent();
        given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.FAILED), any(PageRequest.class)))
                .willReturn(List.of(e1, e2));

        List<OutboxEvent> result = service.findByStatus(OutboxStatus.FAILED, 10);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getStatus() == OutboxStatus.FAILED);
    }

    @Test
    @DisplayName("findByStatus() — limit이 200을 초과하면 200으로 클램프된다")
    void findByStatus_limitsToMax() {
        given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.PENDING), any(PageRequest.class)))
                .willReturn(List.of());

        service.findByStatus(OutboxStatus.PENDING, 999);

        then(outboxEventRepository).should().findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.PENDING), eq(PageRequest.of(0, 200)));
    }

    @Test
    @DisplayName("retryFailedBatch() — FAILED 이벤트 여러 건을 PENDING으로 전환하고 saveAll로 저장한다")
    void retryFailedBatch_resetsAllToPending() {
        OutboxEvent e1 = failedEvent();
        OutboxEvent e2 = failedEvent();
        given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.FAILED), any(PageRequest.class)))
                .willReturn(List.of(e1, e2));
        given(outboxEventRepository.saveAll(List.of(e1, e2))).willReturn(List.of(e1, e2));

        List<OutboxEvent> result = service.retryFailedBatch(OutboxStatus.FAILED, 10, ZonedDateTime.now());

        assertThat(result).hasSize(2);
        assertThat(e1.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e2.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(e1.getSentAt()).isNull();
        assertThat(e2.getSentAt()).isNull();
        then(outboxEventRepository).should(times(1)).saveAll(List.of(e1, e2));
    }

    @Test
    @DisplayName("retryFailedBatch() — FAILED가 아닌 status면 DomainException(OUTBOX_EVENT_NOT_FAILED)이 발생한다")
    void retryFailedBatch_notFailedStatus_throwsDomainException() {
        assertThatThrownBy(() -> service.retryFailedBatch(OutboxStatus.PENDING, 10, ZonedDateTime.now()))
                .isInstanceOf(DomainException.class)
                .satisfies(ex -> assertThat(((DomainException) ex).getCode())
                        .isEqualTo(DomainExceptionCode.OUTBOX_EVENT_NOT_FAILED.name()));

        then(outboxEventRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("retryFailedBatch() — FAILED 이벤트가 없으면 빈 리스트를 반환하고 saveAll을 호출하지 않는다")
    void retryFailedBatch_noEvents_returnsEmpty() {
        given(outboxEventRepository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxStatus.FAILED), any(PageRequest.class)))
                .willReturn(List.of());

        List<OutboxEvent> result = service.retryFailedBatch(OutboxStatus.FAILED, 10, ZonedDateTime.now());

        assertThat(result).isEmpty();
        then(outboxEventRepository).should(never()).saveAll(any());
    }
}
