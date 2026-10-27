package com.credflow.consent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class InternalHttpConfig {

    @Bean
    public RestClient consentInternalRestClient() {

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(4000);

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}