package ru.individuals.api.testsupport.container;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

final class KeycloakTestContainer {

    static final String REALM = "my-realm";
    static final String CLIENT_ID = "my-client-id";
    static final String ADMIN_USERNAME = "admin";
    static final String ADMIN_PASSWORD = "password";

    private static final int HTTP_PORT = 8080;

    private KeycloakTestContainer() {
    }

    static GenericContainer<?> create() {
        return new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.2"))
                .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
                .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("realm-config.json"),
                        "/opt/keycloak/data/import/realm-config.json")
                .withCommand("start-dev", "--import-realm")
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration")
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    static String baseUrl(GenericContainer<?> container) {
        return "http://localhost:" + container.getMappedPort(HTTP_PORT);
    }
}
