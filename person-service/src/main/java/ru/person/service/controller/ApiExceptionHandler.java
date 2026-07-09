package ru.person.service.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.person.service.exception.BadRequestException;
import ru.person.service.exception.EmailAlreadyExistsException;
import ru.person.service.exception.NotFoundException;

import java.net.URI;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Validation error",
                message,
                "https://example.org/problems/validation-error",
                request
        );
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> handleMalformedRequest(Exception exception, HttpServletRequest request) {
        String message = switch (exception) {
            case MethodArgumentTypeMismatchException e -> "Invalid value for parameter '%s'".formatted(e.getName());
            case MissingServletRequestParameterException e ->
                    "Required parameter '%s' is missing".formatted(e.getParameterName());
            default -> "Malformed request body";
        };
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Bad request",
                message,
                "https://example.org/problems/bad-request",
                request
        );
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ProblemDetail> handleBadRequest(BadRequestException exception, HttpServletRequest request) {
        return buildProblem(
                HttpStatus.BAD_REQUEST,
                "Bad request",
                exception.getMessage(),
                "https://example.org/problems/bad-request",
                request
        );
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException exception, HttpServletRequest request) {
        return buildProblem(
                HttpStatus.NOT_FOUND,
                "Resource not found",
                exception.getMessage(),
                "https://example.org/problems/not-found",
                request
        );
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleConflict(
            EmailAlreadyExistsException exception,
            HttpServletRequest request
    ) {
        return buildProblem(
                HttpStatus.CONFLICT,
                "Email already exists",
                exception.getMessage(),
                "https://example.org/problems/email-already-exists",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        return buildProblem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "Unexpected error occurred",
                "https://example.org/problems/internal-server-error",
                request
        );
    }

    private ResponseEntity<ProblemDetail> buildProblem(
            HttpStatus status,
            String title,
            String detail,
            String type,
            HttpServletRequest request
    ) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create(type));
        problemDetail.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemDetail);
    }
}
