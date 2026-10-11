package ru.individuals.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.individuals.api.testsupport.container.IntegrationTestContainers;

import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запрос клиента с Bearer-токеном Keycloak проходит через Individuals API в Transaction Service,
 * который здесь подменён MockWebServer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@Testcontainers(disabledWithoutDocker = true)
class TransactionGatewayIntegrationTest extends IntegrationTestContainers {

    private static final MockWebServer TRANSACTION_SERVICE = startServer();
    private static final String WALLET_TYPE = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void transactionServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("transaction-service.base-url", () -> TRANSACTION_SERVICE.url("/api/v1").toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        TRANSACTION_SERVICE.shutdown();
    }

    @Test
    void createWalletShouldBeProxiedToTransactionServiceWithUserFromToken() throws Exception {
        String accessToken = login();
        String userUid = subject(accessToken);
        String walletUid = UUID.randomUUID().toString();
        TRANSACTION_SERVICE.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"uid":"%s","userUid":"%s","walletTypeUid":"%s","name":"main","currencyCode":"USD",
                         "status":"ACTIVE","balance":0.00,"createdAt":"2026-10-11T10:00:00Z"}
                        """.formatted(walletUid, userUid, WALLET_TYPE)));

        webTestClient.post()
                .uri("/v1/wallets")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"userUid":"%s","walletTypeUid":"%s","name":"main"}
                        """.formatted(userUid, WALLET_TYPE))
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.uid").isEqualTo(walletUid)
                .jsonPath("$.userUid").isEqualTo(userUid);

        RecordedRequest forwarded = TRANSACTION_SERVICE.takeRequest(10, TimeUnit.SECONDS);
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getMethod()).isEqualTo("POST");
        assertThat(forwarded.getPath()).isEqualTo("/api/v1/wallets");
        JsonNode body = objectMapper.readTree(forwarded.getBody().readUtf8());
        assertThat(body.get("userUid").asText()).isEqualTo(userUid);
        assertThat(body.get("name").asText()).isEqualTo("main");
    }

    @Test
    void operationForAnotherUserShouldBeRejectedByGateway() throws Exception {
        String accessToken = login();
        int requestsBefore = TRANSACTION_SERVICE.getRequestCount();

        webTestClient.post()
                .uri("/v1/wallets")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"userUid":"%s","walletTypeUid":"%s","name":"main"}
                        """.formatted(UUID.randomUUID(), WALLET_TYPE))
                .exchange()
                .expectStatus().isForbidden();

        assertThat(TRANSACTION_SERVICE.getRequestCount()).isEqualTo(requestsBefore);
    }

    @Test
    void requestWithoutTokenShouldBeUnauthorized() {
        webTestClient.get()
                .uri("/v1/transactions")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private String login() throws Exception {
        byte[] response = webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"user11@example.com","password":"password"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult()
                .getResponseBody();
        return objectMapper.readTree(response).get("access_token").asText();
    }

    private String subject(String jwt) throws Exception {
        byte[] payload = Base64.getUrlDecoder().decode(jwt.split("\\.")[1]);
        return objectMapper.readTree(payload).get("sub").asText();
    }

    private static MockWebServer startServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return server;
    }
}
