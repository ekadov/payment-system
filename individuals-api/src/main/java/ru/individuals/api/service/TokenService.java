package ru.individuals.api.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.individuals.api.client.keycloak.KeycloakClient;
import ru.individuals.api.dto.TokenRefreshRequest;
import ru.individuals.api.dto.TokenResponse;
import ru.individuals.api.dto.UserLoginRequest;

@Service
@AllArgsConstructor
@Slf4j
public class TokenService {
    private final KeycloakClient keycloakClient;

    public Mono<TokenResponse> login(UserLoginRequest userLoginRequest) {

        if (userLoginRequest == null) {
            log.error("UserLoginRequest is null");
            return Mono.error(new IllegalArgumentException("UserLoginRequest is null"));
        }

        var email = userLoginRequest.getEmail();
        var password = userLoginRequest.getPassword();

        if (email == null || password == null) {
            log.error("email or password is null");
            return Mono.error(new IllegalArgumentException("email or password is null"));
        }
        if (email.isBlank() || password.isBlank()) {
            log.error("email or password is empty");
            return Mono.error(new IllegalArgumentException("email or password is empty"));
        }

        log.info("Login user: {}", userLoginRequest.getEmail());

        return keycloakClient.login(userLoginRequest);
    }

    public Mono<TokenResponse> refresh(TokenRefreshRequest tokenRefreshRequest) {
        if (tokenRefreshRequest == null) {
            log.error("TokenRefreshRequest is null");
            return Mono.error(new IllegalArgumentException("tokenRefreshRequest is null or blank"));
        }
        String refreshToken = tokenRefreshRequest.getRefreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            log.info("Refreshing token");

            return keycloakClient.refresh(tokenRefreshRequest);
        } else {
            log.error("Token is null or blank");
            return Mono.error(new IllegalArgumentException("Token is null or blank"));
        }
    }
}
