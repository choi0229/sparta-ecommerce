package org.teamsparta.addressapi.domain.address.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryPageResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.service.AdminAddressHistoryService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminAddressHistoryController.class)
class AdminAddressHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminAddressHistoryService adminAddressHistoryService;

    private static final AddressHistoryPageResponse EMPTY_PAGE =
            new AddressHistoryPageResponse(List.of(), 0, 20, 0L, 0, false);

    @Test
    @DisplayName("GET /admin/addresses/histories (필터 없음) → 200")
    void searchHistories_noFilters_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/addresses/histories?userId=1 → 200")
    void searchHistories_userIdFilter_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                eq(1L), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories").param("userId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/addresses/histories?actionType=DELETE → 200")
    void searchHistories_actionTypeFilter_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), eq(UserAddressHistory.ActionType.DELETE),
                isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories").param("actionType", "DELETE"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/addresses/histories?from=...&to=... → 200")
    void searchHistories_dateRange_returns200() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), any(), any(), eq(0), eq(20)))
                .thenReturn(EMPTY_PAGE);

        mockMvc.perform(get("/admin/addresses/histories")
                        .param("from", "2024-01-01T00:00:00")
                        .param("to", "2024-12-31T23:59:59"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /admin/addresses/histories?actionType=INVALID → 400")
    void searchHistories_invalidActionType_returns400() throws Exception {
        mockMvc.perform(get("/admin/addresses/histories").param("actionType", "INVALID"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /admin/addresses/histories?page=-1 → 400")
    void searchHistories_negativePage_returns400() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(-1), eq(20)))
                .thenThrow(new IllegalArgumentException("page >= 0, 0 < size <= 100"));

        mockMvc.perform(get("/admin/addresses/histories").param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /admin/addresses/histories?size=101 → 400")
    void searchHistories_sizeTooLarge_returns400() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(101)))
                .thenThrow(new IllegalArgumentException("page >= 0, 0 < size <= 100"));

        mockMvc.perform(get("/admin/addresses/histories").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("from이 to보다 뒤이면 → 400")
    void searchHistories_fromAfterTo_returns400() throws Exception {
        when(adminAddressHistoryService.searchHistories(
                isNull(), isNull(), isNull(), any(), any(), eq(0), eq(20)))
                .thenThrow(new IllegalArgumentException("from must not be after to"));

        mockMvc.perform(get("/admin/addresses/histories")
                        .param("from", "2024-12-31T00:00:00")
                        .param("to", "2024-01-01T00:00:00"))
                .andExpect(status().isBadRequest());
    }
}
