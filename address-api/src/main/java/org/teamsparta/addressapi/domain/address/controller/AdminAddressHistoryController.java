package org.teamsparta.addressapi.domain.address.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryPageResponse;
import org.teamsparta.addressapi.domain.address.entity.UserAddressHistory;
import org.teamsparta.addressapi.domain.address.service.AdminAddressHistoryService;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/admin/addresses/histories")
@RequiredArgsConstructor
public class AdminAddressHistoryController {

    private final AdminAddressHistoryService adminAddressHistoryService;

    @GetMapping
    public AddressHistoryPageResponse searchHistories(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long addressId,
            @RequestParam(required = false) UserAddressHistory.ActionType actionType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return adminAddressHistoryService.searchHistories(userId, addressId, actionType, from, to, page, size);
    }
}
