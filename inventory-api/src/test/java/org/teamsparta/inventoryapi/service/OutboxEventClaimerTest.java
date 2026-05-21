package org.teamsparta.inventoryapi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsparta.inventoryapi.domain.inventory.entity.OutboxEvent;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxEventRepository;
import org.teamsparta.inventoryapi.domain.inventory.repository.OutboxQueryRepository;
import org.teamsparta.inventoryapi.domain.inventory.scheduler.OutboxEventClaimer;
import org.teamsparta.inventoryapi.global.enums.OutboxStatus;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OutboxEventClaimerTest {

    @Mock
    private OutboxQueryRepository outboxQueryRepository;
    @Mock
    private OutboxEventRepository outboxEventRepository;

    private OutboxEventClaimer outboxEventClaimer;

    @BeforeEach
    void setUp() {
        outboxEventClaimer = new OutboxEventClaimer(outboxQueryRepository, outboxEventRepository);
    }

    // ── claimBatch ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("claimBatch — PENDING 이벤트를 PROCESSING 상태로 전이하고 claimedAt을 기록한다")
    void claimBatch_marksPendingAsProcessing() {
        OutboxEvent event = pendingEvent(1L);
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(event));

        List<OutboxEvent> result = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 50);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(result.get(0).getClaimedAt()).isNotNull();
        then(outboxEventRepository).should(times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("claimBatch — 여러 PENDING 이벤트를 모두 PROCESSING으로 전이한다")
    void claimBatch_multipleEvents_allMarkedProcessing() {
        OutboxEvent e1 = pendingEvent(1L);
        OutboxEvent e2 = pendingEvent(2L);
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of(e1, e2));

        List<OutboxEvent> result = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 50);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(e -> e.getStatus() == OutboxStatus.PROCESSING);
        assertThat(result).allMatch(e -> e.getClaimedAt() != null);
        then(outboxEventRepository).should(times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("claimBatch — 빈 배치이면 saveAll을 호출하지 않고 빈 리스트를 반환한다")
    void claimBatch_emptyBatch_noSaveAndReturnsEmpty() {
        given(outboxQueryRepository.findBatchForPublish(any(), anyInt())).willReturn(List.of());

        List<OutboxEvent> result = outboxEventClaimer.claimBatch(ZonedDateTime.now(), 50);

        assertThat(result).isEmpty();
        then(outboxEventRepository).should(never()).saveAll(anyList());
    }

    // ── recoverStaleProcessing ───────────────────────────────────────────────

    @Test
    @DisplayName("recoverStaleProcessing — stale PROCESSING 이벤트를 PENDING으로 되돌리고 nextRetryAt을 초기화한다")
    void recoverStaleProcessing_resetsToPending() {
        OutboxEvent stale = staleProcessingEvent(2L, ZonedDateTime.now().minusMinutes(10));
        given(outboxEventRepository.findStaleProcessingIds(any(), anyInt())).willReturn(List.of(2L));
        given(outboxEventRepository.findAllById(List.of(2L))).willReturn(List.of(stale));

        outboxEventClaimer.recoverStaleProcessing(ZonedDateTime.now().minusMinutes(5), 50);

        assertThat(stale.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(stale.getNextRetryAt()).isNull();
        then(outboxEventRepository).should(times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("recoverStaleProcessing — stale 이벤트의 retryCount는 변경하지 않는다 (publisher 장애이므로)")
    void recoverStaleProcessing_doesNotIncrementRetryCount() {
        OutboxEvent stale = staleProcessingEvent(3L, ZonedDateTime.now().minusMinutes(10));
        int retryCountBefore = stale.getRetryCount();
        given(outboxEventRepository.findStaleProcessingIds(any(), anyInt())).willReturn(List.of(3L));
        given(outboxEventRepository.findAllById(List.of(3L))).willReturn(List.of(stale));

        outboxEventClaimer.recoverStaleProcessing(ZonedDateTime.now().minusMinutes(5), 50);

        assertThat(stale.getRetryCount()).isEqualTo(retryCountBefore);
    }

    @Test
    @DisplayName("recoverStaleProcessing — stale 이벤트가 없으면 saveAll을 호출하지 않는다")
    void recoverStaleProcessing_empty_noSave() {
        given(outboxEventRepository.findStaleProcessingIds(any(), anyInt())).willReturn(List.of());

        outboxEventClaimer.recoverStaleProcessing(ZonedDateTime.now().minusMinutes(5), 50);

        then(outboxEventRepository).should(never()).saveAll(anyList());
        then(outboxEventRepository).should(never()).findAllById(any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private OutboxEvent pendingEvent(Long id) {
        OutboxEvent event = OutboxEvent.pending("Inventory", "SKU-001", "inventory-created-event", "{}");
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    private OutboxEvent staleProcessingEvent(Long id, ZonedDateTime claimedAt) {
        OutboxEvent event = pendingEvent(id);
        event.markProcessing(claimedAt);
        return event;
    }
}
