package org.teamsparta.orderapi.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsparta.orderapi.domain.order.client.AddressClientConfig;
import org.teamsparta.orderapi.domain.order.client.AddressClientProperties;
import org.teamsparta.orderapi.domain.order.client.AddressServiceClient;
import org.teamsparta.orderapi.domain.order.client.HttpAddressServiceClient;
import org.teamsparta.orderapi.domain.order.client.StubAddressServiceClient;

import static org.assertj.core.api.Assertions.assertThat;

class AddressClientConfigTest {

    private final AddressClientConfig config = new AddressClientConfig();

    @Test
    @DisplayName("mode=stub 이면 StubAddressServiceClient를 반환한다")
    void stubMode_returnsStubClient() {
        AddressClientProperties props = new AddressClientProperties();
        props.setMode("stub");

        AddressServiceClient client = config.addressServiceClient(props);

        assertThat(client).isInstanceOf(StubAddressServiceClient.class);
    }

    @Test
    @DisplayName("mode=http 이면 HttpAddressServiceClient를 반환한다")
    void httpMode_returnsHttpClient() {
        AddressClientProperties props = new AddressClientProperties();
        props.setMode("http");
        props.setBaseUrl("http://address-api:8090");

        AddressServiceClient client = config.addressServiceClient(props);

        assertThat(client).isInstanceOf(HttpAddressServiceClient.class);
    }

    @Test
    @DisplayName("mode 대소문자 구분 없이 HTTP 선택 — 'HTTP' 입력")
    void httpMode_caseInsensitive() {
        AddressClientProperties props = new AddressClientProperties();
        props.setMode("HTTP");
        props.setBaseUrl("http://address-api:8090");

        AddressServiceClient client = config.addressServiceClient(props);

        assertThat(client).isInstanceOf(HttpAddressServiceClient.class);
    }

    @Test
    @DisplayName("기본값(mode 미설정)은 StubAddressServiceClient이다")
    void defaultMode_returnsStubClient() {
        AddressClientProperties props = new AddressClientProperties();
        // mode 필드 기본값 = "stub"

        AddressServiceClient client = config.addressServiceClient(props);

        assertThat(client).isInstanceOf(StubAddressServiceClient.class);
    }
}
