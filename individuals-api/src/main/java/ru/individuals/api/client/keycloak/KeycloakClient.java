package ru.individuals.api.client.keycloak;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import ru.individuals.api.client.keycloak.generated.api.DefaultApi;
import ru.individuals.api.client.keycloak.generated.model.KeycloakUserInfoResponse;
import ru.individuals.api.client.keycloak.generated.model.TokenEndpointResponse;
import ru.individuals.api.client.keycloak.mapper.TokenMapper;
import ru.individuals.api.client.keycloak.mapper.UserMapper;
import ru.individuals.api.config.KeycloakProperties;
import ru.individuals.api.dto.*;

import java.net.URI;
import java.time.Duration;

@Component
@RequiredArgsConstructor
public class KeycloakClient {

    private final UserMapper userMapper;
    private final TokenMapper tokenMapper;
    private final DefaultApi keycloakApi;
    private final KeycloakProperties properties;
    private Mono<String> adminAccessToken;

    public Mono<TokenResponse> login(UserLoginRequest request) {
        return exchangeToken(
                "password",
                properties.getClientId(),
                clientSecretOrNull(),
                request.getEmail(),
                request.getPassword(),
                null,
                "openid"
        );
    }

    public Mono<TokenResponse> refresh(TokenRefreshRequest request) {
        return exchangeToken(
                "refresh_token",
                properties.getClientId(),
                clientSecretOrNull(),
                null,
                null,
                request.getRefreshToken(),
                "openid"
        );
    }

    public Mono<String> getAdminAccessToken() {
        if (adminAccessToken == null) {
            adminAccessToken = keycloakApi.exchangeTokenWithResponseSpec(
                            properties.getRealm(),
                            "password",
                            "admin-cli",
                            null,
                            properties.getAdminUsername(),
                            properties.getAdminPassword(),
                            null,
                            null
                    )
                    .onStatus(HttpStatusCode::isError, this::toException)
                    .bodyToMono(TokenEndpointResponse.class)
                    .map(TokenEndpointResponse::getAccessToken)
                    .cache(Duration.ofMinutes(4));
        }

        return adminAccessToken;
    }

    public Mono<String> createUser(String adminAccessToken, UserRegistrationRequest request) {
        var createUserRequestDto = userMapper.toDto(request);
        return keycloakApi.createUserWithResponseSpec(
                        properties.getRealm(),
                        bearer(adminAccessToken),
                        createUserRequestDto
                )
                .onStatus(HttpStatusCode::isError, this::toException)
                .toBodilessEntity()
                .mapNotNull(this::extractCreatedUserId);
    }

    public Mono<KeycloakUserInfoResponse> getUserInfo(String accessToken) {
        return keycloakApi.getUserInfoWithResponseSpec(properties.getRealm(), bearer(accessToken))
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(KeycloakUserInfoResponse.class);
    }

    private Mono<TokenResponse> exchangeToken(String grantType,
                                              String clientId,
                                              String clientSecret,
                                              String username,
                                              String password,
                                              String refreshToken,
                                              String scope) {
        return keycloakApi.exchangeTokenWithResponseSpec(
                        properties.getRealm(),
                        grantType,
                        clientId,
                        clientSecret,
                        username,
                        password,
                        refreshToken,
                        scope
                )
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(ru.individuals.api.client.keycloak.generated.model.TokenEndpointResponse.class)
                .map(tokenMapper::toDto);
    }

    private Mono<RuntimeException> toException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("Empty error body")
                .map(body -> new IllegalStateException("Keycloak request failed with status "
                        + response.statusCode().value() + ": " + body));
    }

    private String clientSecretOrNull() {
        return properties.getClientSecret() == null || properties.getClientSecret().isBlank()
                ? null
                : properties.getClientSecret();
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private String extractCreatedUserId(ResponseEntity<Void> response) {
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new IllegalStateException("No Location returned");
        }

        String path = location.getPath();
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

}
