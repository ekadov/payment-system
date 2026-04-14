package ru.individuals.api.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "keycloak")
@Data
public class KeycloakProperties {
    private String realm;
    private String baseUrl;
    private String clientId;
    private String clientSecret;
    private String adminUsername;
    private String adminPassword;
}
