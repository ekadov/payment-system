package ru.individuals.api.controller;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void handleIllegalArgumentShouldReturnBadRequestErrorResponse() {
        StepVerifier.create(handler.handleIllegalArgument(new IllegalArgumentException("bad input")))
                .assertNext(response -> {
                    assertThat(response.getError()).isEqualTo("bad input");
                    assertThat(response.getStatus()).isEqualTo(400);
                })
                .verifyComplete();
    }

    @Test
    void handleIllegalStateShouldReturnBadGatewayErrorResponse() {
        StepVerifier.create(handler.handleIllegalState(new IllegalStateException("upstream failed")))
                .assertNext(response -> {
                    assertThat(response.getError()).isEqualTo("upstream failed");
                    assertThat(response.getStatus()).isEqualTo(502);
                })
                .verifyComplete();
    }
}
