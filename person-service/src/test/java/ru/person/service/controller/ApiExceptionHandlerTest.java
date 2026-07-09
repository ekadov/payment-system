package ru.person.service.controller;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import ru.person.service.exception.BadRequestException;
import ru.person.service.exception.EmailAlreadyExistsException;
import ru.person.service.exception.NotFoundException;

import java.lang.reflect.Method;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");

    @Test
    void shouldMapNotFoundToProblemDetail() {
        ResponseEntity<ProblemDetail> response =
                handler.handleNotFound(new NotFoundException("missing"), request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(404);
        softAssertions.assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = response.getBody();
        softAssertions.assertThat(body).isNotNull();
        softAssertions.assertThat(body.getTitle()).isNotNull();
        softAssertions.assertThat(body.getTitle()).isEqualTo("Resource not found");
        softAssertions.assertThat(body.getDetail()).isEqualTo("missing");
        softAssertions.assertThat(body.getInstance()).isNotNull();
        softAssertions.assertThat(body.getInstance().toString()).isEqualTo("/api/v1/users");

        softAssertions.assertAll();
    }

    @Test
    void shouldMapConflictToProblemDetail() {
        ResponseEntity<ProblemDetail> response =
                handler.handleConflict(new EmailAlreadyExistsException("dup"), request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(409);
        softAssertions.assertThat(response.getBody().getTitle()).isNotNull();
        softAssertions.assertThat(response.getBody().getTitle()).isEqualTo("Email already exists");
        softAssertions.assertThat(response.getBody().getType().toString())
                .isEqualTo("https://example.org/problems/email-already-exists");

        softAssertions.assertAll();
    }

    @Test
    void shouldMapBadRequestToProblemDetail() {
        ResponseEntity<ProblemDetail> response =
                handler.handleBadRequest(new BadRequestException("bad"), request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(400);
        softAssertions.assertThat(response.getBody().getDetail()).isNotNull();
        softAssertions.assertThat(response.getBody().getDetail()).isEqualTo("bad");

        softAssertions.assertAll();
    }

    @Test
    void shouldMapMissingParameterToBadRequest() {
        ResponseEntity<ProblemDetail> response = handler.handleMalformedRequest(
                new MissingServletRequestParameterException("email", "String"), request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(400);
        softAssertions.assertThat(response.getBody().getDetail()).isNotNull();
        softAssertions.assertThat(response.getBody().getDetail()).contains("email");

        softAssertions.assertAll();
    }

    @Test
    void shouldMapValidationErrorToBadRequest() throws Exception {
        Method method = Dummy.class.getDeclaredMethod("handle", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "createUserRequest");
        bindingResult.addError(new FieldError("createUserRequest", "email", "must not be blank"));
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<ProblemDetail> response = handler.handleValidation(exception, request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(400);
        softAssertions.assertThat(response.getBody().getTitle()).isNotNull();
        softAssertions.assertThat(response.getBody().getTitle()).isEqualTo("Validation error");
        softAssertions.assertThat(response.getBody().getDetail()).contains("email");

        softAssertions.assertAll();
    }

    @Test
    void shouldMapUnexpectedToInternalServerError() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new RuntimeException("boom"), request);

        SoftAssertions softAssertions = new SoftAssertions();

        softAssertions.assertThat(response.getStatusCode().value()).isEqualTo(500);
        softAssertions.assertThat(response.getBody().getTitle()).isNotNull();
        softAssertions.assertThat(response.getBody().getTitle()).isEqualTo("Internal server error");

        softAssertions.assertAll();
    }

    @SuppressWarnings("unused")
    private static final class Dummy {
        void handle(String value) {
        }
    }
}
