package ru.individuals.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import ru.individuals.api.client.keycloak.generated.api.DefaultApi;
import ru.individuals.api.client.keycloak.generated.invoker.ApiClient;

@Configuration
public class KeycloakWebClientConfig {

    @Bean
    public WebClient keycloakWebClient(WebClient.Builder webClientBuilder, KeycloakProperties keycloakProperties) {
        return webClientBuilder
                .baseUrl(keycloakProperties.getBaseUrl())
                .build();
    }

    @Bean
    public DefaultApi keycloakApi(KeycloakProperties keycloakProperties) {
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(keycloakProperties.getBaseUrl());
        return new DefaultApi(apiClient);
    }
}
