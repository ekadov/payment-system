package ru.person.service.controller;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.person.service.generated.dto.CreateUserRequest;
import ru.person.service.generated.dto.UpdateUserRequest;
import ru.person.service.generated.dto.UserResponse;
import ru.person.service.service.UserService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Mock
    private UserService userService;

    private UserController delegate;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        delegate = new UserController(userService);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void createShouldReturn201WithLocation() {
        UUID id = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/users");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(userService.create(any(CreateUserRequest.class))).thenReturn(new UserResponse().id(id));

        ResponseEntity<UserResponse> response = delegate.createUser(new CreateUserRequest());

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(201);
        softAssertions.assertThat(response.getHeaders().getLocation().toString()).endsWith("/api/v1/users/" + id);
        softAssertions.assertThat(response.getBody().getId()).isEqualTo(id);

        softAssertions.assertAll();
    }

    @Test
    void getByIdShouldReturn200() {
        UUID id = UUID.randomUUID();
        when(userService.getById(id)).thenReturn(new UserResponse().id(id));

        ResponseEntity<UserResponse> response = delegate.getUserById(id);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
        softAssertions.assertThat(response.getBody().getId()).isEqualTo(id);

        softAssertions.assertAll();
    }

    @Test
    void getByEmailShouldReturn200() {
        when(userService.getByEmail("a@b.org")).thenReturn(new UserResponse().email("a@b.org"));

        ResponseEntity<UserResponse> response = delegate.getUserByEmail("a@b.org");

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(200);
        softAssertions.assertThat(response.getBody().getEmail()).isEqualTo("a@b.org");

        softAssertions.assertAll();
    }

    @Test
    void updateShouldReturn200() {
        UUID id = UUID.randomUUID();
        when(userService.update(eq(id), any(UpdateUserRequest.class))).thenReturn(new UserResponse().id(id));

        ResponseEntity<UserResponse> response = delegate.updateUser(id, new UpdateUserRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void deleteShouldReturn204() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Void> response = delegate.deleteUser(id);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(userService).delete(id);
    }
}
