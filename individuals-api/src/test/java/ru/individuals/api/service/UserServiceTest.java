package ru.individuals.api.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.individuals.api.client.keycloak.KeycloakClient;
import ru.individuals.api.dto.TokenResponse;
import ru.individuals.api.dto.UserLoginRequest;
import ru.individuals.api.dto.UserRegistrationRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private KeycloakClient keycloakClient;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private UserService userService;

    private static boolean sameLoginCredentials(UserLoginRequest loginRequest) {
        return "user@example.com".equals(loginRequest.getEmail())
                && "password".equals(loginRequest.getPassword());
    }

    @Test
    void registerShouldReturnErrorWhenRequestIsNull() {
        StepVerifier.create(userService.register(null))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("UserRegistrationRequest is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldReturnErrorWhenEmailIsNull() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail(null);
        request.setPassword("password");
        request.setConfirmPassword("password");

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("email is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldReturnErrorWhenPasswordIsNull() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword(null);
        request.setConfirmPassword("password");

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("password is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldReturnErrorWhenConfirmPasswordIsNull() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword(null);

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("confirmPassword is null");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldReturnErrorWhenEmailIsBlank() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail(" ");
        request.setPassword("password");
        request.setConfirmPassword("password");

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("email is empty");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldReturnErrorWhenPasswordIsBlank() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("");
        request.setConfirmPassword("password");

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("password is empty");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldReturnErrorWhenPasswordsDoNotMatch() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("another");

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalArgumentException.class);
                    assertThat(error.getMessage()).isEqualTo("Password and confirmPassword must match");
                })
                .verify();

        verifyNoInteractions(keycloakClient, tokenService);
    }

    @Test
    void registerShouldCreateUserAndLogin() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("password");

        TokenResponse expected = new TokenResponse();
        expected.setAccessToken("access");
        expected.setRefreshToken("refresh");

        when(keycloakClient.getAdminAccessToken()).thenReturn(Mono.just("admin-token"));
        when(keycloakClient.createUser("admin-token", request)).thenReturn(Mono.just("keycloak-id"));
        when(tokenService.login(argThat(login ->
                login.getEmail().equals("user@example.com")
                        && login.getPassword().equals("password"))))
                .thenReturn(Mono.just(expected));

        StepVerifier.create(userService.register(request))
                .assertNext(response -> {
                    assertThat(response.getAccessToken()).isEqualTo("access");
                    assertThat(response.getRefreshToken()).isEqualTo("refresh");
                })
                .verifyComplete();

        verify(keycloakClient).getAdminAccessToken();
        verify(keycloakClient).createUser("admin-token", request);
        verify(tokenService).login(argThat(UserServiceTest::sameLoginCredentials));
    }

    @Test
    void registerShouldPropagateErrorWhenAdminTokenFails() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("password");

        when(keycloakClient.getAdminAccessToken())
                .thenReturn(Mono.error(new IllegalStateException("Keycloak request failed with status 500")));
        when(tokenService.login(any(UserLoginRequest.class))).thenReturn(Mono.empty());

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("500");
                })
                .verify();

        verify(keycloakClient).getAdminAccessToken();
    }

    @Test
    void registerShouldPropagateErrorWhenCreateUserFails() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmail("user@example.com");
        request.setPassword("password");
        request.setConfirmPassword("password");

        when(keycloakClient.getAdminAccessToken()).thenReturn(Mono.just("admin-token"));
        when(keycloakClient.createUser("admin-token", request))
                .thenReturn(Mono.error(new IllegalStateException("Keycloak request failed with status 409: User exists")));
        when(tokenService.login(any(UserLoginRequest.class))).thenReturn(Mono.empty());

        StepVerifier.create(userService.register(request))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("409");
                })
                .verify();
    }
}
