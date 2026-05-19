package org.teamsparta.addressapi.domain.address.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryRetentionDryRunResponse;
import org.teamsparta.addressapi.domain.address.service.AdminAddressHistoryRetentionService;
import org.teamsparta.addressapi.global.security.AdminApiKeyGuard;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAddressHistoryRetentionController.class)
@Import(AdminApiKeyGuard.class)
@TestPropertySource(properties = "address.admin.api-key=test-admin-key")
class AdminAddressHistoryRetentionControllerTest {

    private static final String VALID_KEY = "test-admin-key";
    private static final String URL = "/admin/addresses/histories/retention-dry-run";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAddressHistoryRetentionService retentionService;

    @Test
    @DisplayName("올바른 X-Admin-Api-Key + retentionMonths=12 → 200")
    void retentionDryRun_validKey_returns200() throws Exception {
        LocalDateTime cutoff = LocalDateTime.of(2024, 6, 1, 0, 0);
        when(retentionService.dryRun(12))
                .thenReturn(new AddressHistoryRetentionDryRunResponse(
                        12, cutoff, 5L, "DRY_RUN_ONLY", "현재 정책상 실제 삭제/아카이빙은 수행하지 않음"));

        mockMvc.perform(get(URL)
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("retentionMonths", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retentionMonths").value(12))
                .andExpect(jsonPath("$.candidateCount").value(5))
                .andExpect(jsonPath("$.action").value("DRY_RUN_ONLY"));
    }

    @Test
    @DisplayName("X-Admin-Api-Key 헤더 누락 → 403")
    void retentionDryRun_missingApiKey_returns403() throws Exception {
        mockMvc.perform(get(URL).param("retentionMonths", "12"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("잘못된 X-Admin-Api-Key → 403")
    void retentionDryRun_wrongApiKey_returns403() throws Exception {
        mockMvc.perform(get(URL)
                        .header("X-Admin-Api-Key", "wrong-key")
                        .param("retentionMonths", "12"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("retentionMonths=0 → 400")
    void retentionDryRun_zeroMonths_returns400() throws Exception {
        when(retentionService.dryRun(0))
                .thenThrow(new IllegalArgumentException("retentionMonths must be between 1 and 120"));

        mockMvc.perform(get(URL)
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("retentionMonths", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("retentionMonths=121 → 400")
    void retentionDryRun_tooLargeMonths_returns400() throws Exception {
        when(retentionService.dryRun(121))
                .thenThrow(new IllegalArgumentException("retentionMonths must be between 1 and 120"));

        mockMvc.perform(get(URL)
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("retentionMonths", "121"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("retentionMonths 미전달 → 400")
    void retentionDryRun_missingParam_returns400() throws Exception {
        mockMvc.perform(get(URL).header("X-Admin-Api-Key", VALID_KEY))
                .andExpect(status().isBadRequest());
    }
}
