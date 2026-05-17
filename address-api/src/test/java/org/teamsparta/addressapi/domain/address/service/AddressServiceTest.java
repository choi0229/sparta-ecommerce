package org.teamsparta.addressapi.domain.address.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.addressapi.domain.address.dto.AddressCreateRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressDetailResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressPatchRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.repository.UserAddressHistoryRepository;
import org.teamsparta.addressapi.domain.address.repository.UserAddressRepository;
import org.teamsparta.addressapi.global.exception.AddressNotFoundException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    @Mock
    private UserAddressRepository userAddressRepository;

    @Mock
    private UserAddressHistoryRepository userAddressHistoryRepository;

    @InjectMocks
    private AddressService addressService;

    @Test
    @DisplayName("userId null — 존재하지 않거나 삭제된 주소 조회 시 AddressNotFoundException 발생")
    void findById_notFound_throws() {
        when(userAddressRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.findById(99L, null))
                .isInstanceOf(AddressNotFoundException.class);
    }

    @Test
    @DisplayName("userId null — 활성 주소 조회 시 AddressResponse 반환 (order-api 계약 유지)")
    void findById_active_returnsAddressResponse() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시 강남구", true);
        when(userAddressRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(address));

        AddressResponse result = addressService.findById(1L, null);

        assertThat(result.recipientName()).isEqualTo("홍길동");
        assertThat(result.recipientAddress()).isEqualTo("서울시 강남구");
    }

    @Test
    @DisplayName("userId 일치 — 소유자 검증 통과 후 AddressResponse 반환")
    void findById_ownerMatch_returnsAddressResponse() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시 강남구", true);
        when(userAddressRepository.findByIdAndUserIdAndDeletedFalse(1L, 1L)).thenReturn(Optional.of(address));

        AddressResponse result = addressService.findById(1L, 1L);

        assertThat(result.recipientName()).isEqualTo("홍길동");
        assertThat(result.recipientAddress()).isEqualTo("서울시 강남구");
    }

    @Test
    @DisplayName("userId 불일치 — 소유자 검증 실패 시 AddressNotFoundException 발생")
    void findById_ownerMismatch_throws() {
        when(userAddressRepository.findByIdAndUserIdAndDeletedFalse(1L, 9002L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.findById(1L, 9002L))
                .isInstanceOf(AddressNotFoundException.class);
    }

    @Test
    @DisplayName("userId 일치하더라도 삭제된 주소는 AddressNotFoundException 발생")
    void findById_deleted_ownerMatch_throws() {
        when(userAddressRepository.findByIdAndUserIdAndDeletedFalse(1L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.findById(1L, 1L))
                .isInstanceOf(AddressNotFoundException.class);
    }

    @Test
    @DisplayName("userId로 활성 주소 목록 반환")
    void findByUserId_returnsActiveAddresses() {
        List<UserAddress> addresses = List.of(
                UserAddress.create(1L, "홍길동", "서울시", true),
                UserAddress.create(1L, "홍길동", "부산시", false)
        );
        when(userAddressRepository.findByUserIdAndDeletedFalse(1L)).thenReturn(addresses);

        List<AddressDetailResponse> result = addressService.findByUserId(1L);

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("isDefault=true 주소 생성 시 기존 기본 배송지 초기화 후 저장")
    void create_withDefault_clearsExistingDefaults() {
        AddressCreateRequest request = new AddressCreateRequest(1L, "홍길동", "서울시", true);
        UserAddress saved = UserAddress.create(1L, "홍길동", "서울시", true);
        when(userAddressRepository.save(any())).thenReturn(saved);

        addressService.create(request);

        verify(userAddressRepository).clearDefaultsByUserId(1L);
        verify(userAddressRepository).save(any(UserAddress.class));
    }

    @Test
    @DisplayName("isDefault=false 주소 생성 시 기존 기본 배송지 초기화 없이 저장")
    void create_withoutDefault_doesNotClearDefaults() {
        AddressCreateRequest request = new AddressCreateRequest(1L, "홍길동", "서울시", false);
        UserAddress saved = UserAddress.create(1L, "홍길동", "서울시", false);
        when(userAddressRepository.save(any())).thenReturn(saved);

        addressService.create(request);

        verify(userAddressRepository, never()).clearDefaultsByUserId(any());
        verify(userAddressRepository).save(any(UserAddress.class));
    }

    @Test
    @DisplayName("주소 생성 시 CREATE 이력이 저장된다")
    void create_savesHistory() {
        AddressCreateRequest request = new AddressCreateRequest(1L, "홍길동", "서울시", false);
        UserAddress saved = UserAddress.create(1L, "홍길동", "서울시", false);
        when(userAddressRepository.save(any())).thenReturn(saved);

        addressService.create(request);

        ArgumentCaptor<UserAddressHistory> captor = ArgumentCaptor.forClass(UserAddressHistory.class);
        verify(userAddressHistoryRepository).save(captor.capture());
        UserAddressHistory history = captor.getValue();
        assertThat(history.getActionType()).isEqualTo(UserAddressHistory.ActionType.CREATE);
        assertThat(history.getAfterRecipientName()).isEqualTo("홍길동");
        assertThat(history.getAfterRecipientAddress()).isEqualTo("서울시");
        assertThat(history.getBeforeRecipientName()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 주소 수정 시 AddressNotFoundException 발생")
    void update_notFound_throws() {
        when(userAddressRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.update(99L, new AddressPatchRequest(null, null, null)))
                .isInstanceOf(AddressNotFoundException.class);
    }

    @Test
    @DisplayName("isDefault=true로 수정 시 기존 기본 배송지 초기화")
    void update_setDefault_clearsExistingDefaults() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", false);
        when(userAddressRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(address));
        when(userAddressRepository.save(any())).thenReturn(address);

        addressService.update(1L, new AddressPatchRequest(null, null, true));

        verify(userAddressRepository).clearDefaultsByUserId(1L);
    }

    @Test
    @DisplayName("실제 변경이 있을 때 UPDATE 이력이 저장된다")
    void update_withChange_savesHistory() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", false);
        when(userAddressRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(address));
        when(userAddressRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        addressService.update(1L, new AddressPatchRequest("김철수", null, null));

        ArgumentCaptor<UserAddressHistory> captor = ArgumentCaptor.forClass(UserAddressHistory.class);
        verify(userAddressHistoryRepository).save(captor.capture());
        UserAddressHistory history = captor.getValue();
        assertThat(history.getActionType()).isEqualTo(UserAddressHistory.ActionType.UPDATE);
        assertThat(history.getBeforeRecipientName()).isEqualTo("홍길동");
        assertThat(history.getAfterRecipientName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("변경 내용이 없으면 UPDATE 이력을 저장하지 않는다")
    void update_withNoChange_doesNotSaveHistory() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", false);
        when(userAddressRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(address));
        when(userAddressRepository.save(any())).thenReturn(address);

        addressService.update(1L, new AddressPatchRequest(null, null, null));

        verify(userAddressHistoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("soft delete — deleted 플래그가 설정되고 isDefault가 해제됨")
    void delete_softDeletesAddress() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", true);
        when(userAddressRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(address));

        addressService.delete(1L);

        assertThat(address.isDeleted()).isTrue();
        assertThat(address.isDefault()).isFalse();
        verify(userAddressRepository).save(address);
    }

    @Test
    @DisplayName("주소 삭제 시 DELETE 이력이 삭제 전 값으로 저장된다")
    void delete_savesHistory() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", true);
        when(userAddressRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(address));

        addressService.delete(1L);

        ArgumentCaptor<UserAddressHistory> captor = ArgumentCaptor.forClass(UserAddressHistory.class);
        verify(userAddressHistoryRepository).save(captor.capture());
        UserAddressHistory history = captor.getValue();
        assertThat(history.getActionType()).isEqualTo(UserAddressHistory.ActionType.DELETE);
        assertThat(history.getBeforeRecipientName()).isEqualTo("홍길동");
        assertThat(history.getBeforeIsDefault()).isTrue();
        assertThat(history.getAfterRecipientName()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 주소 삭제 시 AddressNotFoundException 발생")
    void delete_notFound_throws() {
        when(userAddressRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.delete(99L))
                .isInstanceOf(AddressNotFoundException.class);
    }

    // ── 이력 조회 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("본인 userId로 이력 조회 시 이력 목록 반환")
    void findHistories_ownerMatch_returnsHistories() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", false);
        UserAddressHistory h = UserAddressHistory.forCreate(address);
        when(userAddressRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(address));
        when(userAddressHistoryRepository.findByAddressIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(h));

        List<AddressHistoryResponse> result = addressService.findHistories(1L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).actionType()).isEqualTo("CREATE");
    }

    @Test
    @DisplayName("다른 userId로 이력 조회 시 AddressNotFoundException 발생")
    void findHistories_ownerMismatch_throws() {
        when(userAddressRepository.findByIdAndUserId(1L, 9002L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.findHistories(1L, 9002L))
                .isInstanceOf(AddressNotFoundException.class);
    }

    @Test
    @DisplayName("soft delete된 주소라도 본인 userId면 이력 조회 가능")
    void findHistories_deletedAddress_ownerMatch_returnsHistories() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", false);
        address.softDelete();
        UserAddressHistory h = UserAddressHistory.forDelete(address);
        when(userAddressRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(address));
        when(userAddressHistoryRepository.findByAddressIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(h));

        List<AddressHistoryResponse> result = addressService.findHistories(1L, 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).actionType()).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("이력은 changedAt DESC 순서로 반환된다 (리포지토리 정렬 위임 검증)")
    void findHistories_returnedInRepoOrder() {
        UserAddress address = UserAddress.create(1L, "홍길동", "서울시", false);
        LocalDateTime older = LocalDateTime.now().minusHours(1);
        LocalDateTime newer = LocalDateTime.now();

        UserAddressHistory first = UserAddressHistory.forCreate(address);
        UserAddressHistory second = UserAddressHistory.forUpdate(
                null, 1L,
                "홍길동", "서울시", false,
                "김철수", "부산시", false);

        when(userAddressRepository.findByIdAndUserId(1L, 1L)).thenReturn(Optional.of(address));
        when(userAddressHistoryRepository.findByAddressIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(second, first));

        List<AddressHistoryResponse> result = addressService.findHistories(1L, 1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).actionType()).isEqualTo("UPDATE");
        assertThat(result.get(1).actionType()).isEqualTo("CREATE");
    }
}
