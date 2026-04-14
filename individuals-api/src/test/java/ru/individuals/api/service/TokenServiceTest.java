package ru.individuals.api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.individuals.api.client.keycloak.KeycloakClient;
import ru.individuals.api.dto.TokenRefreshRequest;
import ru.individuals.api.dto.TokenResponse;
import ru.individuals.api.dto.UserLoginRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private KeycloakClient keycloakClient;

    @InjectMocks
    private TokenService tokenService;

    @Test
    void loginShouldReturnErrorWhenRequestIsNull() {
        StepVerifier.create(tokenService.login(null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("UserLoginRequest is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void loginShouldReturnErrorWhenEmailIsNull() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail(null);
        request.setPassword("password");

        StepVerifier.create(tokenService.login(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("email or password is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void loginShouldReturnErrorWhenPasswordIsNull() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("user@example.com");
        request.setPassword(null);

        StepVerifier.create(tokenService.login(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("email or password is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void loginShouldReturnErrorWhenEmailIsBlank() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail(" ");
        request.setPassword("password");

        StepVerifier.create(tokenService.login(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("email or password is empty");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void loginShouldReturnErrorWhenPasswordIsBlank() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("");

        StepVerifier.create(tokenService.login(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("email or password is empty");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void loginShouldPropagateKeycloakError() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");

        when(keycloakClient.login(request))
                .thenReturn(Mono.error(new IllegalStateException("Keycloak request failed with status 401")));

        StepVerifier.create(tokenService.login(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("401");
                })
                .verify();
    }

    @Test
    void loginShouldDelegateToKeycloakClient() {
        UserLoginRequest request = new UserLoginRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");

        TokenResponse expected = new TokenResponse();
        expected.setAccessToken("access");
        expected.setRefreshToken("refresh");

        when(keycloakClient.login(request)).thenReturn(Mono.just(expected));

        StepVerifier.create(tokenService.login(request))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("access");
                    assertThat(response.getRefreshToken()).isEqualTo("refresh");
                })
                .verifyComplete();

        verify(keycloakClient).login(request);
    }

    @Test
    void refreshShouldReturnErrorWhenRequestIsNull() {
        StepVerifier.create(tokenService.refresh(null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("tokenRefreshRequest is null or blank");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void refreshShouldReturnErrorWhenTokenIsBlank() {
        TokenRefreshRequest request = new TokenRefreshRequest();
        request.setRefreshToken(" ");

        StepVerifier.create(tokenService.refresh(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("Token is null or blank");
                })
                .verify();

        verifyNoInteractions(keycloakClient);
    }

    @Test
    void refreshShouldDelegateToKeycloakClient() {
        TokenRefreshRequest request = new TokenRefreshRequest();
        request.setRefreshToken("refresh-token");

        TokenResponse expected = new TokenResponse();
        expected.setAccessToken("new-access");
        expected.setRefreshToken("new-refresh");

        when(keycloakClient.refresh(request)).thenReturn(Mono.just(expected));

        StepVerifier.create(tokenService.refresh(request))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("new-access");
                    assertThat(response.getRefreshToken()).isEqualTo("new-refresh");
                })
                .verifyComplete();

        verify(keycloakClient).refresh(request);
    }
}
