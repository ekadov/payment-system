package ru.individuals.api.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import ru.individuals.api.client.keycloak.KeycloakClient;
import ru.individuals.api.dto.TokenResponse;
import ru.individuals.api.dto.UserLoginRequest;
import ru.individuals.api.dto.UserRegistrationRequest;

@Service
@AllArgsConstructor
@Slf4j
public class UserService {
    private final KeycloakClient keycloakClient;
    private final TokenService tokenService;

    public Mono<TokenResponse> register(UserRegistrationRequest userRegistrationRequest) {
        if (userRegistrationRequest == null) {
            log.error("UserRegistrationRequest is null");
            return Mono.error(new IllegalArgumentException("UserRegistrationRequest is null"));
        }
        if (userRegistrationRequest.getEmail() == null) {
            log.error("email is null");
            return Mono.error(new IllegalArgumentException("email is null"));
        }
        if (userRegistrationRequest.getPassword() == null) {
            log.error("Password is null");
            return Mono.error(new IllegalArgumentException("password is null"));
        }
        if (userRegistrationRequest.getConfirmPassword() == null) {
            log.error("ConfirmPassword is null");
            return Mono.error(new IllegalArgumentException("confirmPassword is null"));
        }
        if (userRegistrationRequest.getPassword().isBlank()) {
            log.error("Password is empty");
            return Mono.error(new IllegalArgumentException("password is empty"));
        }
        if (userRegistrationRequest.getConfirmPassword().isBlank()) {
            log.error("ConfirmPassword is empty");
            return Mono.error(new IllegalArgumentException("confirmPassword is empty"));
        }
        if (userRegistrationRequest.getEmail().isBlank()) {
            log.error("Email is empty");
            return Mono.error(new IllegalArgumentException("email is empty"));
        }
        if (!userRegistrationRequest.getPassword().equals(userRegistrationRequest.getConfirmPassword())) {
            log.error("Password and ConfirmPassword mismatch");
            return Mono.error(new IllegalArgumentException("Password and confirmPassword must match"));
        }

        log.info("Registering user: {}", userRegistrationRequest.getEmail());

        return keycloakClient.getAdminAccessToken()
                .flatMap(adminAccessToken ->
                        keycloakClient.createUser(adminAccessToken, userRegistrationRequest))
                .then(tokenService.login(toLoginRequest(userRegistrationRequest)));
    }

    private UserLoginRequest toLoginRequest(UserRegistrationRequest userRegistrationRequest) {
        UserLoginRequest loginRequest = new UserLoginRequest();
        loginRequest.setEmail(userRegistrationRequest.getEmail());
        loginRequest.setPassword(userRegistrationRequest.getPassword());
        return loginRequest;
    }
}
