package org.teamsparta.orderapi.domain.order.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@EnableConfigurationProperties(AddressClientProperties.class)
public class AddressClientConfig {

    @Bean
    public AddressServiceClient addressServiceClient(AddressClientProperties props) {
        if ("http".equalsIgnoreCase(props.getMode())) {
            log.info("AddressServiceClient mode=http, baseUrl={}", props.getBaseUrl());
            return new HttpAddressServiceClient(
                    props.getBaseUrl(), props.getConnectTimeoutMs(), props.getReadTimeoutMs());
        }
        log.info("AddressServiceClient mode=stub");
        return new StubAddressServiceClient();
    }
}
