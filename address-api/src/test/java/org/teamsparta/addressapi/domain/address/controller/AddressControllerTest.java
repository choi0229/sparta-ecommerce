package org.teamsparta.addressapi.domain.address.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.addressapi.domain.address.dto.AddressHistoryResponse;
import org.teamsparta.addressapi.domain.address.dto.AddressResponse;
import org.teamsparta.addressapi.domain.address.service.AddressService;
import org.teamsparta.addressapi.global.exception.AddressNotFoundException;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AddressController.class)
class AddressControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AddressService addressService;

    @Test
    @DisplayName("POST /addresses — userId null → 400")
    void createAddress_nullUserId_returns400() throws Exception {
        mockMvc.perform(post("/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":null,"recipientName":"홍길동","recipientAddress":"서울시","isDefault":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /addresses — recipientName blank → 400")
    void createAddress_blankRecipientName_returns400() throws Exception {
        mockMvc.perform(post("/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"recipientName":"  ","recipientAddress":"서울시","isDefault":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /addresses — recipientAddress blank → 400")
    void createAddress_blankRecipientAddress_returns400() throws Exception {
        mockMvc.perform(post("/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"recipientName":"홍길동","recipientAddress":"","isDefault":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /addresses/{id} — recipientName blank → 400")
    void updateAddress_blankRecipientName_returns400() throws Exception {
        mockMvc.perform(patch("/addresses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"  ","recipientAddress":null,"isDefault":null}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /addresses/{id} — recipientAddress blank → 400")
    void updateAddress_blankRecipientAddress_returns400() throws Exception {
        mockMvc.perform(patch("/addresses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":null,"recipientAddress":"","isDefault":null}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /addresses/{id} — 모든 필드 null → 200 (미수정 허용)")
    void updateAddress_allNull_returns200() throws Exception {
        mockMvc.perform(patch("/addresses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":null,"recipientAddress":null,"isDefault":null}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /addresses — partial unique index 충돌(기본 배송지 중복) → 409")
    void createAddress_defaultConflict_returns409() throws Exception {
        when(addressService.create(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "could not execute statement; constraint [ux_user_address_default_active]"));

        mockMvc.perform(post("/addresses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":1,"recipientName":"홍길동","recipientAddress":"서울시","isDefault":true}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /addresses/{id}?userId=정상소유자 → 200")
    void getAddress_ownerMatch_returns200() throws Exception {
        when(addressService.findById(1L, 1L))
                .thenReturn(new AddressResponse("홍길동", "서울시 강남구"));

        mockMvc.perform(get("/addresses/1").param("userId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /addresses/{id}?userId=다른사용자 → 404")
    void getAddress_ownerMismatch_returns404() throws Exception {
        when(addressService.findById(1L, 9002L))
                .thenThrow(new AddressNotFoundException(1L));

        mockMvc.perform(get("/addresses/1").param("userId", "9002"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /addresses/{id}/histories?userId=소유자 → 200")
    void listHistories_ownerMatch_returns200() throws Exception {
        AddressHistoryResponse resp = new AddressHistoryResponse(
                1L, 1L, 1L, "CREATE",
                null, null, null,
                "홍길동", "서울시", false,
                LocalDateTime.now());
        when(addressService.findHistories(1L, 1L)).thenReturn(List.of(resp));

        mockMvc.perform(get("/addresses/1/histories").param("userId", "1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /addresses/{id}/histories?userId=다른사용자 → 404")
    void listHistories_ownerMismatch_returns404() throws Exception {
        when(addressService.findHistories(1L, 9002L))
                .thenThrow(new AddressNotFoundException(1L));

        mockMvc.perform(get("/addresses/1/histories").param("userId", "9002"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /addresses/{id}/histories — userId 미전달 → 400")
    void listHistories_missingUserId_returns400() throws Exception {
        mockMvc.perform(get("/addresses/1/histories"))
                .andExpect(status().isBadRequest());
    }
}
