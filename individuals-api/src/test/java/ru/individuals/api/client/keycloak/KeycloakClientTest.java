package ru.individuals.api.client.keycloak;

import com.github.javafaker.Faker;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import reactor.test.StepVerifier;
import ru.individuals.api.client.keycloak.generated.api.DefaultApi;
import ru.individuals.api.client.keycloak.generated.invoker.ApiClient;
import ru.individuals.api.client.keycloak.mapper.TokenMapper;
import ru.individuals.api.client.keycloak.mapper.UserMapper;
import ru.individuals.api.config.KeycloakProperties;
import ru.individuals.api.dto.TokenRefreshRequest;
import ru.individuals.api.dto.UserLoginRequest;
import ru.individuals.api.dto.UserRegistrationRequest;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakClientTest {

    private MockWebServer server;
    private KeycloakClient client;

    private static Map<String, String> parseForm(String body) {
        Map<String, String> result = new HashMap<>();
        if (body == null || body.isEmpty()) {
            return result;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq >= 0 ? pair.substring(0, eq) : pair, StandardCharsets.UTF_8);
            String value = eq >= 0 ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        KeycloakProperties properties = new KeycloakProperties();
        properties.setRealm("test-realm");
        properties.setBaseUrl(server.url("/").toString());
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        properties.setAdminUsername("admin");
        properties.setAdminPassword("admin-password");

        client = new KeycloakClient(
                UserMapper.INSTANCE,
                Mappers.getMapper(TokenMapper.class),
                keycloakApi(properties),
                properties);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void loginShouldPostPasswordGrantAndMapTokenResponse() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {
                          "access_token": "access",
                          "refresh_token": "refresh",
                          "expires_in": 3600,
                          "token_type": "Bearer"
                        }
                        """));

        UserLoginRequest request = new UserLoginRequest();
        var email = Faker.instance().internet().emailAddress();
        var password = Faker.instance().internet().password();

        request.setEmail(email);
        request.setPassword(password);

        StepVerifier.create(client.login(request))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("access");
                    assertThat(response.getRefreshToken()).isEqualTo("refresh");
                    assertThat(response.getExpiresIn()).isEqualTo(3600);
                    assertThat(response.getTokenType()).isEqualTo("Bearer");
                })
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/realms/test-realm/protocol/openid-connect/token");
        assertThat(recorded.getHeader(HttpHeaders.CONTENT_TYPE))
                .startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);

        Map<String, String> form = parseForm(recorded.getBody().readUtf8());
        assertThat(form)
                .containsEntry("grant_type", "password")
                .containsEntry("client_id", "test-client")
                .containsEntry("client_secret", "test-secret")
                .containsEntry("username", email)
                .containsEntry("password", password)
                .containsEntry("scope", "openid");
    }

    @Test
    void loginShouldOmitClientSecretWhenBlank() throws InterruptedException {
        KeycloakProperties properties = new KeycloakProperties();
        properties.setRealm("test-realm");
        properties.setBaseUrl(server.url("/").toString());
        properties.setClientId("public-client");
        properties.setClientSecret("");
        KeycloakClient publicClient = new KeycloakClient(
                UserMapper.INSTANCE,
                Mappers.getMapper(TokenMapper.class),
                keycloakApi(properties),
                properties);

        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"access_token\":\"a\",\"refresh_token\":\"r\",\"expires_in\":1,\"token_type\":\"Bearer\"}"));

        UserLoginRequest request = new UserLoginRequest();
        var email = Faker.instance().internet().emailAddress();
        var password = Faker.instance().internet().password();

        request.setEmail(email);
        request.setPassword(password);

        StepVerifier.create(publicClient.login(request)).expectNextCount(1).verifyComplete();

        Map<String, String> form = parseForm(server.takeRequest().getBody().readUtf8());
        assertThat(form).doesNotContainKey("client_secret");
    }

    @Test
    void loginShouldPropagateIllegalStateExceptionOnHttpError() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"error\":\"invalid_grant\"}"));

        UserLoginRequest request = new UserLoginRequest();
        var email = Faker.instance().internet().emailAddress();
        var password = Faker.instance().internet().password();

        request.setEmail(email);
        request.setPassword(password);

        StepVerifier.create(client.login(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage())
                            .contains("401")
                            .contains("invalid_grant");
                })
                .verify();
    }

    @Test
    void refreshShouldPostRefreshTokenGrant() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"access_token\":\"new-a\",\"refresh_token\":\"new-r\",\"expires_in\":60,\"token_type\":\"Bearer\"}"));

        TokenRefreshRequest request = new TokenRefreshRequest();
        request.setRefreshToken("old-refresh-token");

        StepVerifier.create(client.refresh(request))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("new-a");
                    assertThat(response.getRefreshToken()).isEqualTo("new-r");
                })
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/realms/test-realm/protocol/openid-connect/token");
        Map<String, String> form = parseForm(recorded.getBody().readUtf8());
        assertThat(form)
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "old-refresh-token")
                .containsEntry("client_id", "test-client")
                .containsEntry("client_secret", "test-secret");
    }

    @Test
    void refreshShouldPropagateErrorOnHttpError() {
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("Bad refresh token"));

        TokenRefreshRequest request = new TokenRefreshRequest();
        request.setRefreshToken("expired");

        StepVerifier.create(client.refresh(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage())
                            .contains("400")
                            .contains("Bad refresh token");
                })
                .verify();
    }

    @Test
    void getAdminAccessTokenShouldReturnAccessTokenField() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"access_token\":\"admin-token\",\"token_type\":\"Bearer\"}"));

        StepVerifier.create(client.getAdminAccessToken())
                .expectNext("admin-token")
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/realms/test-realm/protocol/openid-connect/token");

        Map<String, String> form = parseForm(recorded.getBody().readUtf8());
        assertThat(form)
                .containsEntry("grant_type", "password")
                .containsEntry("client_id", "admin-cli")
                .containsEntry("username", "admin")
                .containsEntry("password", "admin-password");
    }

    @Test
    void getAdminAccessTokenShouldPropagateErrorOnHttpError() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("boom"));

        StepVerifier.create(client.getAdminAccessToken())
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("500").contains("boom");
                })
                .verify();
    }

    @Test
    void createUserShouldSendBearerAndReturnId() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setResponseCode(201)
                .setHeader(HttpHeaders.LOCATION, "/admin/realms/test-realm/users/keycloak-user-id"));

        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("password");

        StepVerifier.create(client.createUser("admin-token", request))
                .expectNext("keycloak-user-id")
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath()).isEqualTo("/admin/realms/test-realm/users");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer admin-token");
        assertThat(recorded.getHeader(HttpHeaders.CONTENT_TYPE))
                .startsWith(MediaType.APPLICATION_JSON_VALUE);

        String body = recorded.getBody().readUtf8();
        assertThat(body)
                .contains("\"email\":\"user@example.com\"")
                .contains("\"username\":\"user@example.com\"")
                .contains("\"enabled\":true")
                .contains("\"emailVerified\":true")
                .contains("\"type\":\"password\"")
                .contains("\"value\":\"password\"")
                .contains("\"temporary\":false");
    }

    @Test
    void createUserShouldPropagateErrorOnHttpError() {
        server.enqueue(new MockResponse()
                .setResponseCode(409)
                .setBody("{\"errorMessage\":\"User exists\"}"));

        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("password");

        StepVerifier.create(client.createUser("admin-token", request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("409").contains("User exists");
                })
                .verify();
    }

    @Test
    void createUserShouldFailWhenLocationHeaderIsMissing() {
        server.enqueue(new MockResponse().setResponseCode(201));

        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("password");

        StepVerifier.create(client.createUser("admin-token", request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("No Location returned");
                })
                .verify();
    }

    @Test
    void getUserInfoShouldSendBearerAndReturnResponse() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"sub\":\"user-id\",\"email\":\"user@example.com\"}"));

        StepVerifier.create(client.getUserInfo("user-access-token"))
                .assertNext(info -> {
                    assertThat(info).isNotNull();
                    assertThat(info.getSub()).isEqualTo("user-id");
                    assertThat(info.getEmail()).isEqualTo("user@example.com");
                })
                .verifyComplete();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getMethod()).isEqualTo("GET");
        assertThat(recorded.getPath()).isEqualTo("/realms/test-realm/protocol/openid-connect/userinfo");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer user-access-token");
    }

    @Test
    void getUserInfoShouldPropagateErrorOnHttpError() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

        StepVerifier.create(client.getUserInfo("bad-token"))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("401").contains("unauthorized");
                })
                .verify();
    }

    private DefaultApi keycloakApi(KeycloakProperties properties) {
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(properties.getBaseUrl());
        return new DefaultApi(apiClient);
    }
}
