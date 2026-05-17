package org.teamsparta.addressapi.domain.address.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.addressapi.domain.address.dto.AddressCreateRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressDetailResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryPageResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressPatchRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.repository.UserAddressHistoryRepository;
import org.teamsparta.addressapi.domain.address.repository.UserAddressRepository;
import org.teamsparta.addressapi.global.exception.AddressNotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

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
        UserAddressHistory autoUnsetHistory = null;
        if (request.isDefault()) {
            // clearDefaultsByUserId 전에 기존 default를 조회해 이력 값을 캡처한다.
            // clearAutomatically=true bulk update 이후에는 L1 캐시가 초기화되므로 미리 확보한다.
            autoUnsetHistory = userAddressRepository
                    .findByUserIdAndIsDefaultTrueAndDeletedFalse(request.userId())
                    .map(existing -> UserAddressHistory.forUpdate(
                            existing.getId(), existing.getUserId(),
                            existing.getRecipientName(), existing.getRecipientAddress(), true,
                            existing.getRecipientName(), existing.getRecipientAddress(), false))
                    .orElse(null);
            userAddressRepository.clearDefaultsByUserId(request.userId());
        }
        UserAddress address = UserAddress.create(
                request.userId(),
                request.recipientName(),
                request.recipientAddress(),
                request.isDefault()
        );
        UserAddress saved = userAddressRepository.save(address);
        if (autoUnsetHistory != null) {
            userAddressHistoryRepository.save(autoUnsetHistory);
        }
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

        // 수정 대상이 이미 기본 배송지가 아닐 때만 다른 기존 default를 찾는다.
        // isDefault=true인 채로 isDefault=true를 다시 요청하면 자기 자신만 영향을 받으므로 이력 불필요.
        UserAddressHistory autoUnsetHistory = null;
        if (Boolean.TRUE.equals(request.isDefault()) && !address.isDefault()) {
            autoUnsetHistory = userAddressRepository
                    .findByUserIdAndIsDefaultTrueAndDeletedFalse(address.getUserId())
                    .map(existing -> UserAddressHistory.forUpdate(
                            existing.getId(), existing.getUserId(),
                            existing.getRecipientName(), existing.getRecipientAddress(), true,
                            existing.getRecipientName(), existing.getRecipientAddress(), false))
                    .orElse(null);
        }

        if (Boolean.TRUE.equals(request.isDefault())) {
            userAddressRepository.clearDefaultsByUserId(address.getUserId());
        }
        address.update(request.recipientName(), request.recipientAddress(), request.isDefault());
        UserAddress saved = userAddressRepository.save(address);

        if (autoUnsetHistory != null) {
            userAddressHistoryRepository.save(autoUnsetHistory);
        }

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
    public AddressHistoryPageResponse findHistories(
            Long addressId, Long userId, int page, int size,
            UserAddressHistory.ActionType actionType) {
        if (page < 0 || size <= 0 || size > 100) {
            throw new IllegalArgumentException("page >= 0, 0 < size <= 100");
        }
        userAddressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressNotFoundException(addressId));
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<UserAddressHistory> result = (actionType != null)
                ? userAddressHistoryRepository.findByAddressIdAndActionType(addressId, actionType, pageable)
                : userAddressHistoryRepository.findByAddressId(addressId, pageable);
        return AddressHistoryPageResponse.from(result);
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
