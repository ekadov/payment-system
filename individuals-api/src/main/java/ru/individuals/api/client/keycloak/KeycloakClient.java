package ru.individuals.api.client.keycloak;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import ru.individuals.api.client.keycloak.dto.KeycloakUserInfoResponse;
import ru.individuals.api.config.KeycloakProperties;
import ru.individuals.api.dto.*;

import java.util.List;
import java.util.Map;

@Component
@AllArgsConstructor
public class KeycloakClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final WebClient webClient;
    private final KeycloakProperties properties;

    public Mono<TokenResponse> login(UserLoginRequest request) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "password");
        formData.add("client_id", properties.getClientId());
        formData.add("username", request.getEmail());
        formData.add("password", request.getPassword());
        formData.add("scope", "openid");
        addClientSecret(formData);
        return exchangeToken(formData);
    }

    public Mono<TokenResponse> refresh(TokenRefreshRequest request) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "refresh_token");
        formData.add("client_id", properties.getClientId());
        formData.add("refresh_token", request.getRefreshToken());
        formData.add("scope", "openid");
        addClientSecret(formData);
        return exchangeToken(formData);
    }

    public Mono<String> getAdminAccessToken() {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "password");
        formData.add("client_id", "admin-cli");
        formData.add("username", properties.getAdminUsername());
        formData.add("password", properties.getAdminPassword());

        return webClient.post()
                .uri(tokenPath())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(TokenEndpointResponse.class)
                .map(TokenEndpointResponse::accessToken);
    }

    public Mono<String> createUser(String adminAccessToken, UserRegistrationRequest request) {
        CreateUserRequest body = new CreateUserRequest(
                request.getEmail(),
                request.getEmail(),
                true,
                List.of(new CredentialRepresentation("password", request.getPassword(), false)),
                true
        );

        return webClient.post()
                .uri(usersAdminPath())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminAccessToken))
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(UserInfoResponse.class)
                .map(UserInfoResponse::getId);
    }

    public Mono<KeycloakUserInfoResponse> getUserInfo(String accessToken) {
        return webClient.get()
                .uri(userInfoPath())
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(KeycloakUserInfoResponse.class);
    }

    private Mono<TokenResponse> exchangeToken(MultiValueMap<String, String> formData) {
        return webClient.post()
                .uri(tokenPath())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(formData))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(TokenEndpointResponse.class)
                .map(TokenEndpointResponse::toDto);
    }

    private Mono<RuntimeException> toException(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("Empty error body")
                .map(body -> new IllegalStateException("Keycloak request failed with status "
                        + response.statusCode().value() + ": " + body));
    }

    private void addClientSecret(MultiValueMap<String, String> formData) {
        if (properties.getClientSecret() != null && !properties.getClientSecret().isBlank()) {
            formData.add("client_secret", properties.getClientSecret());
        }
    }

    private String tokenPath() {
        return "/realms/" + properties.getRealm() + "/protocol/openid-connect/token";
    }

    private String userInfoPath() {
        return "/realms/" + properties.getRealm() + "/protocol/openid-connect/userinfo";
    }

    private String usersAdminPath() {
        return "/admin/realms/" + properties.getRealm() + "/users";
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private record CreateUserRequest(
            String email,
            String username,
            boolean enabled,
            List<CredentialRepresentation> credentials,
            boolean emailVerified
    ) {
    }

    private record CredentialRepresentation(
            String type,
            String value,
            boolean temporary
    ) {
    }

    private record TokenEndpointResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Integer expiresIn,
            @JsonProperty("token_type") String tokenType
    ) {
        private TokenResponse toDto() {
            TokenResponse response = new TokenResponse();
            response.setAccessToken(accessToken);
            response.setRefreshToken(refreshToken);
            response.setExpiresIn(expiresIn);
            response.setTokenType(tokenType);
            return response;
        }
    }
}
