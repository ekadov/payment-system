package ru.transaction.service.exception;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
