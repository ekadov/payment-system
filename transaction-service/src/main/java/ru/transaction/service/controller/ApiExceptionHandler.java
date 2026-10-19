package ru.transaction.service.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.exception.ConflictException;
import ru.transaction.service.exception.ForbiddenException;
import ru.transaction.service.exception.IdempotencyKeyReusedException;
import ru.transaction.service.exception.NotFoundException;
import ru.transaction.service.exception.WalletAlreadyExistsException;
import ru.transaction.service.generated.dto.ErrorResponse;

import java.net.URI;

@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception exception, HttpServletRequest request) {
        log.warn("Bad request: uri={}, error={}", request.getRequestURI(), exception.getMessage());
        return response(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(ForbiddenException exception, HttpServletRequest request) {
        log.warn("Forbidden: uri={}, error={}", request.getRequestURI(), exception.getMessage());
        return response(HttpStatus.FORBIDDEN, exception.getMessage(), request);
    }

    @ExceptionHandler({NotFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> notFound(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler({ConflictException.class, WalletAlreadyExistsException.class})
    public ResponseEntity<ErrorResponse> conflict(Exception exception, HttpServletRequest request) {
        log.warn("Conflict: uri={}, error={}", request.getRequestURI(), exception.getMessage());
        return response(HttpStatus.CONFLICT, exception.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    public ResponseEntity<ErrorResponse> idempotencyKeyReused(IdempotencyKeyReusedException exception,
                                                              HttpServletRequest request) {
        log.warn("Idempotency key reused: uri={}, error={}", request.getRequestURI(), exception.getMessage());
        return response(HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request);
    }

    /**
     * Нарушение уникальности при гонке (имя кошелька, ключ идемпотентности).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> integrityViolation(DataIntegrityViolationException exception,
                                                            HttpServletRequest request) {
        log.warn("Data integrity violation: uri={}, error={}", request.getRequestURI(),
                exception.getMostSpecificCause().getMessage());
        return response(HttpStatus.CONFLICT, "Request conflicts with the current state of the resource", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof org.springframework.web.ErrorResponse springError) {
            HttpStatus status = HttpStatus.valueOf(springError.getStatusCode().value());
            return response(status, exception.getMessage(), request);
        }
        log.error("Unexpected error: uri={}", request.getRequestURI(), exception);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", request);
    }

    private ResponseEntity<ErrorResponse> response(HttpStatus status, String detail, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(status.getReasonPhrase(), status.value(), detail)
                .type(URI.create("about:blank"))
                .instance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
