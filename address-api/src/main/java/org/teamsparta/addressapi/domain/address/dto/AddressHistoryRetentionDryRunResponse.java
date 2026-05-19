package org.teamsparta.addressapi.domain.address.dto;

import java.time.LocalDateTime;

public record AddressHistoryRetentionDryRunResponse(
        int retentionMonths,
        LocalDateTime cutoffAt,
        long candidateCount,
        String action,
        String message
) {
    public static final String ACTION = "DRY_RUN_ONLY";
    public static final String MESSAGE = "현재 정책상 실제 삭제/아카이빙은 수행하지 않음 (ADR-002 D안)";
}
