package ru.individuals.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class KeycloakWebClientConfig {

    @Bean
    public WebClient keycloakWebClient(WebClient.Builder webClientBuilder, KeycloakProperties keycloakProperties) {
        return webClientBuilder
                .baseUrl(keycloakProperties.getBaseUrl())
                .build();
    }
}
