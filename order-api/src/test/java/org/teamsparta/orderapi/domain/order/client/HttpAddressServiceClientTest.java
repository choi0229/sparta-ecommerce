package org.teamsparta.orderapi.domain.order.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpAddressServiceClientTest {

    private static final String BASE_URL = "http://address-api";

    private MockRestServiceServer server;
    private HttpAddressServiceClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new HttpAddressServiceClient(BASE_URL, restTemplate);
    }

    @Test
    @DisplayName("정상 응답이면 AddressInfo를 반환한다")
    void findById_success_returnsAddressInfo() {
        server.expect(requestTo(BASE_URL + "/addresses/1?userId=1"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(
                      "{\"recipientName\":\"홍길동\",\"recipientAddress\":\"서울시 강남구 테헤란로 1\"}",
                      MediaType.APPLICATION_JSON));

        AddressServiceClient.AddressInfo info = client.findById(1L, 1L);

        assertThat(info.recipientName()).isEqualTo("홍길동");
        assertThat(info.recipientAddress()).isEqualTo("서울시 강남구 테헤란로 1");
        server.verify();
    }

    @Test
    @DisplayName("404 응답이면 ADDRESS_NOT_FOUND 예외가 발생한다 (소유자 불일치 포함)")
    void findById_404_throwsAddressNotFound() {
        server.expect(requestTo(BASE_URL + "/addresses/999?userId=1"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.findById(999L, 1L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(DomainExceptionCode.ADDRESS_NOT_FOUND.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("5xx 응답이면 ADDRESS_LOOKUP_FAILED 예외가 발생한다")
    void findById_500_throwsAddressLookupFailed() {
        server.expect(requestTo(BASE_URL + "/addresses/1?userId=1"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.findById(1L, 1L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(DomainExceptionCode.ADDRESS_LOOKUP_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("서비스 unavailable(503) 이면 ADDRESS_LOOKUP_FAILED 예외가 발생한다")
    void findById_503_throwsAddressLookupFailed() {
        server.expect(requestTo(BASE_URL + "/addresses/1?userId=1"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.findById(1L, 1L))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining(DomainExceptionCode.ADDRESS_LOOKUP_FAILED.getMessage());
        server.verify();
    }

    @Test
    @DisplayName("userId가 URL 쿼리 파라미터로 포함되어 호출된다")
    void findById_includesUserIdInUrl() {
        server.expect(requestTo(BASE_URL + "/addresses/42?userId=9001"))
              .andExpect(method(HttpMethod.GET))
              .andRespond(withSuccess(
                      "{\"recipientName\":\"smoke-tester\",\"recipientAddress\":\"smoke-addr-real\"}",
                      MediaType.APPLICATION_JSON));

        client.findById(42L, 9001L);

        server.verify();
    }
}
