package ru.individuals.api.config;

import io.micrometer.observation.ObservationRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.individuals.api.client.transaction.generated.api.WalletsApi;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionServiceTokenRelayTest {

    private MockWebServer server;
    private WalletsApi walletsApi;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        TransactionClientConfig config = new TransactionClientConfig();
        walletsApi = config.walletsApi(config.transactionApiClient(server.url("/api/v1").toString(),
                ObservationRegistry.NOOP));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void userTokenIsForwardedOnlyWithinCall() throws Exception {
        UUID userUid = UUID.randomUUID();
        Jwt jwt = Jwt.withTokenValue("user-access-token").header("alg", "none").subject(userUid.toString()).build();
        server.enqueue(emptyList());
        server.enqueue(emptyList());

        List<?> wallets = TransactionServiceTokenRelay.callAs(jwt, () -> walletsApi.getUserWallets(userUid));
        walletsApi.getUserWallets(userUid);

        assertThat(wallets).isEmpty();
        RecordedRequest relayed = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(relayed.getHeader("Authorization")).isEqualTo("Bearer user-access-token");
        RecordedRequest withoutUser = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(withoutUser.getHeader("Authorization")).as("токен не утекает в следующие вызовы потока").isNull();
    }

    private static MockResponse emptyList() {
        return new MockResponse().setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).setBody("[]");
    }
}
