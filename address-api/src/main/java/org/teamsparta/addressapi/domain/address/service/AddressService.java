package org.teamsparta.addressapi.domain.address.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.addressapi.domain.address.dto.AddressCreateRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressDetailResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressPatchRequest;
import org.teamsparta.addressapi.domain.address.dto.AddressResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;
import org.teamsparta.addressapi.domain.address.repository.UserAddressRepository;
import org.teamsparta.addressapi.global.exception.AddressNotFoundException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final UserAddressRepository userAddressRepository;

    // order-api 연동용 — 기존 응답 계약 유지
    @Transactional(readOnly = true)
    public AddressResponse findById(Long id) {
        UserAddress address = userAddressRepository.findByIdAndDeletedFalse(id)
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
        return AddressDetailResponse.from(userAddressRepository.save(address));
    }

    @Transactional
    public AddressDetailResponse update(Long id, AddressPatchRequest request) {
        UserAddress address = userAddressRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new AddressNotFoundException(id));
        if (Boolean.TRUE.equals(request.isDefault())) {
            userAddressRepository.clearDefaultsByUserId(address.getUserId());
        }
        address.update(request.recipientName(), request.recipientAddress(), request.isDefault());
        return AddressDetailResponse.from(userAddressRepository.save(address));
    }

    @Transactional
    public void delete(Long id) {
        UserAddress address = userAddressRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new AddressNotFoundException(id));
        address.softDelete();
        userAddressRepository.save(address);
    }
}
