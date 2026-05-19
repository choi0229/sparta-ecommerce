package org.teamsparta.addressapi.domain.address.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryRetentionDryRunResponse;
import org.teamsparta.addressapi.domain.address.repository.UserAddressHistoryRepository;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminAddressHistoryRetentionService {

    private final UserAddressHistoryRepository userAddressHistoryRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AddressHistoryRetentionDryRunResponse dryRun(int retentionMonths) {
        if (retentionMonths <= 0 || retentionMonths > 120) {
            throw new IllegalArgumentException("retentionMonths must be between 1 and 120");
        }
        LocalDateTime cutoffAt = LocalDateTime.now(clock).minusMonths(retentionMonths);
        long candidateCount = userAddressHistoryRepository.countByCreatedAtBefore(cutoffAt);
        return new AddressHistoryRetentionDryRunResponse(
                retentionMonths,
                cutoffAt,
                candidateCount,
                AddressHistoryRetentionDryRunResponse.ACTION,
                AddressHistoryRetentionDryRunResponse.MESSAGE);
    }
}
