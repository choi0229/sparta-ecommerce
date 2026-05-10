package org.teamsparta.orderapi.domain.order.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "address.client")
@Getter
@Setter
public class AddressClientProperties {

    private String mode = "stub";
    private String baseUrl = "http://address-api:8090";
    private int connectTimeoutMs = 1000;
    private int readTimeoutMs = 2000;
}
