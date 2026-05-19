package org.teamsparta.addressapi.domain.address.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryRetentionDryRunResponse;
import org.teamsparta.addressapi.domain.address.service.AdminAddressHistoryRetentionService;
import org.teamsparta.addressapi.global.security.AdminApiKeyGuard;

@RestController
@RequestMapping("/admin/addresses/histories")
@RequiredArgsConstructor
public class AdminAddressHistoryRetentionController {

    private final AdminAddressHistoryRetentionService retentionService;
    private final AdminApiKeyGuard adminApiKeyGuard;

    @GetMapping("/retention-dry-run")
    public AddressHistoryRetentionDryRunResponse retentionDryRun(
            @RequestHeader(value = "X-Admin-Api-Key", required = false) String apiKey,
            @RequestParam int retentionMonths) {
        adminApiKeyGuard.validate(apiKey);
        return retentionService.dryRun(retentionMonths);
    }
}
