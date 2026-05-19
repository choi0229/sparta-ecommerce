package org.teamsparta.addressapi.domain.address.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryPageResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.service.AdminAddressHistoryService;
import org.teamsparta.addressapi.global.security.AdminApiKeyGuard;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAddressHistoryController.class)
@Import(AdminApiKeyGuard.class)
@TestPropertySource(properties = "address.admin.api-key=test-admin-key")
class AdminAddressHistoryControllerTest {

    private static final String VALID_KEY = "test-admin-key";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAddressHistoryService adminAddressHistoryService;

    private static final AddressHistoryPageResponse EMPTY_PAGE =
            new AddressHistoryPageResponse(List.of(), 0, 20, 0L, 0, false);

    @Test
    @DisplayName("올바른 API 키 + 필터 없음 → 200")
    void searchHistories_validKey_noFilters_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("X-Admin-Api-Key 헤더 누락 → 403")
    void searchHistories_missingApiKey_returns403() throws Exception {
        mockMvc.perform(get("/admin/addresses/histories"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("잘못된 API 키 → 403")
    void searchHistories_wrongApiKey_returns403() throws Exception {
        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", "wrong-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("올바른 API 키 + userId 필터 → 200")
    void searchHistories_validKey_userIdFilter_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                eq(1L), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("userId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("올바른 API 키 + actionType=DELETE → 200")
    void searchHistories_validKey_actionTypeFilter_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), eq(UserAddressHistory.ActionType.DELETE),
                isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("actionType", "DELETE"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("올바른 API 키 + 날짜 범위 필터 → 200")
    void searchHistories_validKey_dateRange_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), any(), any(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("from", "2024-01-01T00:00:00")
                        .param("to", "2024-12-31T23:59:59"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("올바른 API 키 + actionType=INVALID → 400")
    void searchHistories_validKey_invalidActionType_returns400() throws Exception {
        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("actionType", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("올바른 API 키 + page=-1 → 400")
    void searchHistories_validKey_negativePage_returns400() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(-1), eq(20)))
                .thenThrow(new IllegalArgumentException("page >= 0, 0 < size <= 100"));

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("올바른 API 키 + size=101 → 400")
    void searchHistories_validKey_sizeTooLarge_returns400() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(101)))
                .thenThrow(new IllegalArgumentException("page >= 0, 0 < size <= 100"));

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("올바른 API 키 + from > to → 400")
    void searchHistories_validKey_fromAfterTo_returns400() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), any(), any(), eq(0), eq(20)))
                .thenThrow(new IllegalArgumentException("from must not be after to"));

        mockMvc.perform(get("/admin/addresses/histories")
                        .header("X-Admin-Api-Key", VALID_KEY)
                        .param("from", "2024-12-31T00:00:00")
                        .param("to", "2024-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }
}
