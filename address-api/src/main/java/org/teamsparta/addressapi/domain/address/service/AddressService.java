package org.teamsparta.addressapi.domain.address.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final UserAddressRepository userAddressRepository;
    private final UserAddressHistoryRepository userAddressHistoryRepository;

    // order-api 연동용 — 응답 계약 유지. userId가 null이면 소유자 검증 없이 조회.
    @Transactional(readOnly = true)
    public AddressResponse findById(Long id, Long userId) {
        UserAddress address = (userId != null)
                ? userAddressRepository.findByIdAndUserIdAndDeletedFalse(id, userId)
                        .orElseThrow(() -> new AddressNotFoundException(id))
                : userAddressRepository.findByIdAndDeletedFalse(id)
                        .orElseThrow(() -> new AddressNotFoundException(id));
        return new AddressResponse(address.getRecipientName(), address.getRecipientAddress());
    }

    @Transactional(readOnly = true)
    public List<AddressDetailResponse> findByUserId(Long userId) {
        return userAddressRepository.findByUserIdAndDeletedFalse(userId).stream()
                .map(AddressDetailResponse::from)
                .toList();
    }

    @Transactional
    public AddressDetailResponse create(AddressCreateRequest request) {
        if (request.isDefault()) {
            userAddressRepository.clearDefaultsByUserId(request.userId());
        }
        UserAddress address = UserAddress.create(
                request.userId(),
                request.recipientName(),
                request.recipientAddress(),
                request.isDefault()
        );
        UserAddress saved = userAddressRepository.save(address);
        userAddressHistoryRepository.save(UserAddressHistory.forCreate(saved));
        return AddressDetailResponse.from(saved);
    }

    @Transactional
    public AddressDetailResponse update(Long id, AddressPatchRequest request) {
        UserAddress address = userAddressRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new AddressNotFoundException(id));

        String beforeName = address.getRecipientName();
        String beforeAddr = address.getRecipientAddress();
        boolean beforeDefault = address.isDefault();

        if (Boolean.TRUE.equals(request.isDefault())) {
            userAddressRepository.clearDefaultsByUserId(address.getUserId());
        }
        address.update(request.recipientName(), request.recipientAddress(), request.isDefault());
        UserAddress saved = userAddressRepository.save(address);

        boolean changed = !Objects.equals(beforeName, saved.getRecipientName())
                || !Objects.equals(beforeAddr, saved.getRecipientAddress())
                || beforeDefault != saved.isDefault();
        if (changed) {
            userAddressHistoryRepository.save(
                    UserAddressHistory.forUpdate(
                            saved.getId(), saved.getUserId(),
                            beforeName, beforeAddr, beforeDefault,
                            saved.getRecipientName(), saved.getRecipientAddress(), saved.isDefault()));
        }
        return AddressDetailResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<AddressHistoryResponse> findHistories(Long addressId, Long userId) {
        userAddressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        return userAddressHistoryRepository.findByAddressIdOrderByCreatedAtDesc(addressId).stream()
                .map(AddressHistoryResponse::from)
                .toList();
    }

    @Transactional
    public void delete(Long id) {
        UserAddress address = userAddressRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new AddressNotFoundException(id));
        UserAddressHistory history = UserAddressHistory.forDelete(address);
        address.softDelete();
        userAddressRepository.save(address);
        userAddressHistoryRepository.save(history);
    }
}
