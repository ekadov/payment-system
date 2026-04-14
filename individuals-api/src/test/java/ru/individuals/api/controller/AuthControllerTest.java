package ru.individuals.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import ru.individuals.api.config.SecurityConfig;
import ru.individuals.api.dto.TokenRefreshRequest;
import ru.individuals.api.dto.TokenResponse;
import ru.individuals.api.dto.UserLoginRequest;
import ru.individuals.api.dto.UserRegistrationRequest;
import ru.individuals.api.service.TokenService;
import ru.individuals.api.service.UserService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

@WebFluxTest(controllers = AuthController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private ReactiveJwtDecoder reactiveJwtDecoder;

    private static TokenResponse tokenResponse(String access, String refresh) {
        TokenResponse response = new TokenResponse();
        response.setAccessToken(access);
        response.setRefreshToken(refresh);
        response.setTokenType("Bearer");
        response.setExpiresIn(3600);
        return response;
    }

    @Test
    void registerShouldReturnCreated() {
        TokenResponse response = tokenResponse("access", "refresh");
        when(userService.register(any(UserRegistrationRequest.class))).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user@example.com",
                          "password": "password",
                          "confirm_password": "password"
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("access")
                .jsonPath("$.refresh_token").isEqualTo("refresh");
    }

    @Test
    void registerShouldReturnBadRequestWhenServiceThrowsIllegalArgument() {
        when(userService.register(any(UserRegistrationRequest.class)))
                .thenReturn(Mono.error(new IllegalArgumentException("Password and confirmPassword must match")));

        webTestClient.post()
                .uri("/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user@example.com",
                          "password": "password",
                          "confirm_password": "mismatch"
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Password and confirmPassword must match")
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void registerShouldReturnBadGatewayWhenKeycloakFails() {
        when(userService.register(any(UserRegistrationRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("Keycloak request failed with status 409")));

        webTestClient.post()
                .uri("/v1/auth/registration")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user@example.com",
                          "password": "password",
                          "confirm_password": "password"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502)
                .jsonPath("$.error").isEqualTo("Keycloak request failed with status 409");
    }

    @Test
    void loginShouldReturnOkOnSuccess() {
        TokenResponse response = tokenResponse("access", "refresh");
        when(tokenService.login(any(UserLoginRequest.class))).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user@example.com",
                          "password": "password"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("access")
                .jsonPath("$.refresh_token").isEqualTo("refresh");
    }

    @Test
    void loginShouldReturnBadRequestWhenServiceThrowsIllegalArgument() {
        when(tokenService.login(any(UserLoginRequest.class)))
                .thenReturn(Mono.error(new IllegalArgumentException("email or password is empty")));

        webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "",
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
    void loginShouldReturnBadGatewayWhenKeycloakFails() {
        when(tokenService.login(any(UserLoginRequest.class)))
                .thenReturn(Mono.error(new IllegalStateException("Keycloak request failed with status 401: invalid_grant")));

        webTestClient.post()
                .uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "email": "user@example.com",
                          "password": "wrong"
                        }
                        """)
                .exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.status").isEqualTo(502);
    }

    @Test
    void refreshShouldReturnBadRequestWhenTokenIsBlank() {
        when(tokenService.refresh(any(TokenRefreshRequest.class)))
                .thenReturn(Mono.error(new IllegalArgumentException("Token is null or blank")));

        webTestClient.post()
                .uri("/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "refresh_token": " "
                        }
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Token is null or blank")
                .jsonPath("$.status").isEqualTo(400);
    }

    @Test
    void meShouldReturnUnauthorizedWithoutJwt() {
        webTestClient.get()
                .uri("/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void refreshShouldReturnOk() {
        TokenResponse response = tokenResponse("new-access", "new-refresh");
        when(tokenService.refresh(any(TokenRefreshRequest.class))).thenReturn(Mono.just(response));

        webTestClient.post()
                .uri("/v1/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "refresh_token": "refresh-token"
                        }
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.access_token").isEqualTo("new-access")
                .jsonPath("$.refresh_token").isEqualTo("new-refresh");
    }

    @Test
    void meShouldReturnCurrentUserFromJwtClaims() {
        webTestClient
                .mutateWith(mockJwt().jwt(jwt -> jwt.claim("sub", "user-id").claim("email", "user@example.com")))
                .get()
                .uri("/v1/auth/me")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo("user-id")
                .jsonPath("$.email").isEqualTo("user@example.com");
    }
}
