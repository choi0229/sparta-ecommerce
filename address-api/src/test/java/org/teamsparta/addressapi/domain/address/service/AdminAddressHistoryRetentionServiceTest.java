package org.teamsparta.addressapi.domain.address.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryRetentionDryRunResponse;
import org.teamsparta.addressapi.domain.address.repository.UserAddressHistoryRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAddressHistoryRetentionServiceTest {

    @Mock
    private UserAddressHistoryRepository userAddressHistoryRepository;

    private AdminAddressHistoryRetentionService service;

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final Instant FIXED_INSTANT = Instant.parse("2025-06-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, ZONE);

    @BeforeEach
    void setUp() {
        service = new AdminAddressHistoryRetentionService(userAddressHistoryRepository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("retentionMonths=12 → cutoffAt 기준 candidateCount 조회 및 response 반영")
    void dryRun_12months_returnsCorrectResponse() {
        LocalDateTime expectedCutoffAt = LocalDateTime.now(FIXED_CLOCK).minusMonths(12);
        when(userAddressHistoryRepository.countByCreatedAtBefore(expectedCutoffAt)).thenReturn(42L);

        AddressHistoryRetentionDryRunResponse response = service.dryRun(12);

        assertThat(response.retentionMonths()).isEqualTo(12);
        assertThat(response.cutoffAt()).isEqualTo(expectedCutoffAt);
        assertThat(response.candidateCount()).isEqualTo(42L);
        assertThat(response.action()).isEqualTo("DRY_RUN_ONLY");
        assertThat(response.message()).contains("삭제/아카이빙은 수행하지 않음");
    }

    @Test
    @DisplayName("candidateCount가 0이어도 정상 응답")
    void dryRun_zeroCount_returnsEmptyResponse() {
        LocalDateTime expectedCutoffAt = LocalDateTime.now(FIXED_CLOCK).minusMonths(6);
        when(userAddressHistoryRepository.countByCreatedAtBefore(expectedCutoffAt)).thenReturn(0L);

        AddressHistoryRetentionDryRunResponse response = service.dryRun(6);

        assertThat(response.candidateCount()).isZero();
    }

    @Test
    @DisplayName("retentionMonths=120 (최대값) → 정상 처리")
    void dryRun_maxMonths_passes() {
        LocalDateTime expectedCutoffAt = LocalDateTime.now(FIXED_CLOCK).minusMonths(120);
        when(userAddressHistoryRepository.countByCreatedAtBefore(expectedCutoffAt)).thenReturn(0L);

        AddressHistoryRetentionDryRunResponse response = service.dryRun(120);

        assertThat(response.retentionMonths()).isEqualTo(120);
    }

    @Test
    @DisplayName("retentionMonths <= 0 → IllegalArgumentException")
    void dryRun_zeroMonths_throws() {
        assertThatThrownBy(() -> service.dryRun(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionMonths");
    }

    @Test
    @DisplayName("retentionMonths < 0 → IllegalArgumentException")
    void dryRun_negativeMonths_throws() {
        assertThatThrownBy(() -> service.dryRun(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("retentionMonths > 120 → IllegalArgumentException")
    void dryRun_tooLargeMonths_throws() {
        assertThatThrownBy(() -> service.dryRun(121))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retentionMonths");
    }
}
