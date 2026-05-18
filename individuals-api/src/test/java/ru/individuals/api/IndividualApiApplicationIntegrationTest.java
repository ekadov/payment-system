package ru.individuals.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.individuals.api.testsupport.container.IntegrationTestContainers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@Testcontainers(disabledWithoutDocker = true)
class IndividualApiApplicationIntegrationTest extends IntegrationTestContainers {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void actuatorHealthShouldBeUp() {
        webTestClient.get()
                .uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void meShouldReturnUnauthorizedWithoutBearerToken() {
        webTestClient.get()
                .uri("/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void loginShouldReturnTokensForPreconfiguredUser() {
        webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user11@example.com",
                          "password": "password"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").exists()
                .jsonPath("$.refresh_token").exists()
                .jsonPath("$.token_type").isEqualTo("Bearer");
    }

    @Test
    void loginShouldReturnBadGatewayForWrongPassword() {
        webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user11@example.com",
                          "password": "wrong-password"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502);
    }

    @Test
    void loginShouldReturnBadRequestForBlankEmail() {
        webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": " ",
                          "password": "password"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("email or password is empty")
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void registerShouldCreateUserAndReturnTokensAndThenMeIsReachable() {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        AtomicReference<String> accessTokenRef = new AtomicReference<>();
        AtomicReference<String> refreshTokenRef = new AtomicReference<>();

        webTestClient.post()
                .uri("/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "%s",
                          "password": "password",
                          "confirm_password": "password"
                        }
                        """.formatted(email))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.access_token").value(v -> accessTokenRef.set((String) v))
                .jsonPath("$.refresh_token").value(v -> refreshTokenRef.set((String) v));

        webTestClient.get()
                .uri("/v1/auth/me")
                .header("Authorization", "Bearer " + accessTokenRef.get())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(email);

        webTestClient.post()
                .uri("/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "refresh_token": "%s"
                        }
                        """.formatted(refreshTokenRef.get()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").exists()
                .jsonPath("$.refresh_token").exists();
    }

    @Test
    void registerShouldReturnBadRequestWhenPasswordsMismatch() {
        webTestClient.post()
                .uri("/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "mismatch@example.com",
                          "password": "password",
                          "confirm_password": "other"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Password and confirmPassword must match")
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void refreshShouldReturnBadGatewayForInvalidToken() {
        webTestClient.post()
                .uri("/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "refresh_token": "not-a-real-token"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502);
    }
}
