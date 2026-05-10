package org.teamsparta.addressapi.domain.address.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsparta.addressapi.domain.address.dto.AddressResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddress;
import org.teamsparta.addressapi.domain.address.repository.UserAddressRepository;
import org.teamsparta.addressapi.global.exception.AddressNotFoundException;

@Service
@RequiredArgsConstructor
public class AddressService {

    private final UserAddressRepository userAddressRepository;

    @Transactional(readOnly = true)
    public AddressResponse findById(Long id) {
        UserAddress address = userAddressRepository.findById(id)
                .orElseThrow(() -> new AddressNotFoundException(id));
        return new AddressResponse(address.getRecipientName(), address.getRecipientAddress());
    }
}
