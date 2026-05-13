package org.teamsparta.addressapi.domain.address.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.teamsparta.addressapi.domain.address.service.AddressService;

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
}
