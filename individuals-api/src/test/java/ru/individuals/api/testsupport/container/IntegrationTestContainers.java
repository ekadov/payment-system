package ru.individuals.api.testsupport.container;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

public abstract class IntegrationTestContainers {

    @Container
    static final GenericContainer<?> KEYCLOAK = KeycloakTestContainer.create();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        String baseUrl = KeycloakTestContainer.baseUrl(KEYCLOAK);

        registry.add("keycloak.base-url", () -> baseUrl);
        registry.add("keycloak.realm", () -> KeycloakTestContainer.REALM);
        registry.add("keycloak.client-id", () -> KeycloakTestContainer.CLIENT_ID);
        registry.add("keycloak.client-secret", () -> "");
        registry.add("keycloak.admin-username", () -> KeycloakTestContainer.ADMIN_USERNAME);
        registry.add("keycloak.admin-password", () -> KeycloakTestContainer.ADMIN_PASSWORD);
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> baseUrl + "/realms/" + KeycloakTestContainer.REALM
        );
    }
}
