package org.teamsparta.orderapi.domain.order.client;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.teamsparta.orderapi.global.exception.DomainException;
import org.teamsparta.orderapi.global.exception.DomainExceptionCode;

public class HttpAddressServiceClient implements AddressServiceClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public HttpAddressServiceClient(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl;
    }

    HttpAddressServiceClient(String baseUrl, RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public AddressInfo findById(Long addressId) {
        try {
            AddressApiResponse response = restTemplate.getForObject(
                    baseUrl + "/addresses/" + addressId, AddressApiResponse.class);
            if (response == null) {
                throw new DomainException(DomainExceptionCode.ADDRESS_LOOKUP_FAILED);
            }
            return new AddressInfo(response.recipientName(), response.recipientAddress());
        } catch (HttpClientErrorException.NotFound e) {
            throw new DomainException(DomainExceptionCode.ADDRESS_NOT_FOUND);
        } catch (DomainException e) {
            throw e;
        } catch (Exception e) {
            throw new DomainException(DomainExceptionCode.ADDRESS_LOOKUP_FAILED);
        }
    }

    private record AddressApiResponse(String recipientName, String recipientAddress) {}
}
