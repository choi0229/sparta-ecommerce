package org.teamsparta.addressapi.domain.address.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryPageResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.repository.UserAddressHistoryRepository;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAddressHistoryServiceTest {

    @Mock
    private UserAddressHistoryRepository userAddressHistoryRepository;

    @InjectMocks
    private AdminAddressHistoryService adminAddressHistoryService;

    private static final PageImpl<UserAddressHistory> EMPTY_PAGE =
            new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);

    @Test
    @DisplayName("필터 없이 전체 조회 — empty page 반환")
    void searchHistories_noFilters_returnsPage() {
        when(userAddressHistoryRepository.findAllByFilters(
                isNull(), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(EMPTY_PAGE);

        AddressHistoryPageResponse response =
                adminAddressHistoryService.searchHistories(null, null, null, null, null, 0, 20);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    @DisplayName("userId 필터 전달 확인")
    void searchHistories_userIdFilter_passedToRepository() {
        when(userAddressHistoryRepository.findAllByFilters(
                eq(1L), isNull(), isNull(), isNull(), isNull(), any()))
                .thenReturn(EMPTY_PAGE);

        adminAddressHistoryService.searchHistories(1L, null, null, null, null, 0, 20);

        verify(userAddressHistoryRepository)
                .findAllByFilters(eq(1L), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("actionType 필터 전달 확인")
    void searchHistories_actionTypeFilter_passedToRepository() {
        when(userAddressHistoryRepository.findAllByFilters(
                isNull(), isNull(), eq(UserAddressHistory.ActionType.UPDATE), isNull(), isNull(), any()))
                .thenReturn(EMPTY_PAGE);

        adminAddressHistoryService.searchHistories(null, null,
                UserAddressHistory.ActionType.UPDATE, null, null, 0, 20);

        verify(userAddressHistoryRepository)
                .findAllByFilters(isNull(), isNull(),
                        eq(UserAddressHistory.ActionType.UPDATE), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("날짜 범위 필터 전달 확인")
    void searchHistories_dateRangeFilter_passedToRepository() {
        LocalDateTime from = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime to = LocalDateTime.of(2024, 12, 31, 23, 59, 59);
        when(userAddressHistoryRepository.findAllByFilters(
                isNull(), isNull(), isNull(), eq(from), eq(to), any()))
                .thenReturn(EMPTY_PAGE);

        adminAddressHistoryService.searchHistories(null, null, null, from, to, 0, 20);

        verify(userAddressHistoryRepository)
                .findAllByFilters(isNull(), isNull(), isNull(), eq(from), eq(to), any());
    }

    @Test
    @DisplayName("from이 to보다 뒤이면 IllegalArgumentException")
    void searchHistories_fromAfterTo_throws() {
        LocalDateTime from = LocalDateTime.of(2024, 12, 31, 0, 0);
        LocalDateTime to = LocalDateTime.of(2024, 1, 1, 0, 0);

        assertThatThrownBy(() ->
                adminAddressHistoryService.searchHistories(null, null, null, from, to, 0, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    @Test
    @DisplayName("page < 0 → IllegalArgumentException")
    void searchHistories_negativePage_throws() {
        assertThatThrownBy(() ->
                adminAddressHistoryService.searchHistories(null, null, null, null, null, -1, 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("size > 100 → IllegalArgumentException")
    void searchHistories_sizeTooLarge_throws() {
        assertThatThrownBy(() ->
                adminAddressHistoryService.searchHistories(null, null, null, null, null, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Pageable 정렬이 createdAt DESC인지 확인")
    void searchHistories_pageableSort_createdAtDesc() {
        ArgumentCaptor<org.springframework.data.domain.Pageable> captor =
                ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(userAddressHistoryRepository.findAllByFilters(
                isNull(), isNull(), isNull(), isNull(), isNull(), captor.capture()))
                .thenReturn(EMPTY_PAGE);

        adminAddressHistoryService.searchHistories(null, null, null, null, null, 0, 20);

        assertThat(captor.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }
}
