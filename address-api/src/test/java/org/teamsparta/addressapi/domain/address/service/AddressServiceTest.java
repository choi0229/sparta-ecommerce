package org.teamsparta.addressapi.domain.address.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsparta.addressapi.domain.address.dto.AddressCreateRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressDetailResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressPatchRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;
import org.teamsparta.addressapi.domain.address.repository.UserAddressRepository;
import org.teamsparta.addressapi.global.exception.AddressNotFoundException;

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
    @DisplayName("존재하지 않는 주소 삭제 시 AddressNotFoundException 발생")
    void delete_notFound_throws() {
        when(userAddressRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> addressService.delete(99L))
                .isInstanceOf(AddressNotFoundException.class);
    }
}
