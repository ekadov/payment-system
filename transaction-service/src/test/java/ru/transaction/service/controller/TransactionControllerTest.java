package ru.transaction.service.controller;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import ru.transaction.service.generated.dto.TransactionConfirmResponse;
import ru.transaction.service.generated.dto.TransactionRequest;
import ru.transaction.service.generated.dto.TransactionStatus;
import ru.transaction.service.generated.dto.TransactionType;
import ru.transaction.service.metrics.TransactionMetrics;
import ru.transaction.service.service.TransactionService;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    private final UUID userUid = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    @Mock
    private TransactionService transactionService;
    private TransactionController controller;
    private TransactionRequest request;

    @BeforeEach
    void setUp() {
        controller = new TransactionController(transactionService, new TransactionMetrics(new SimpleMeterRegistry()),
                new CurrentUser());
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(userUid.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        request = new TransactionRequest(userUid, UUID.randomUUID(), new BigDecimal("10.00"))
                .currency("USD").paymentMethodId(1L);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * Параллельный запрос с тем же ключом закоммитил транзакцию первым: этот получил нарушение уникальности,
     * его транзакция откатилась, и он возвращает результат победителя.
     */
    @Test
    void lostRaceForIdempotencyKeyReturnsWinnersTransaction() {
        TransactionConfirmResponse winner = new TransactionConfirmResponse(UUID.randomUUID(), TransactionType.WITHDRAWAL,
                TransactionStatus.PENDING, new BigDecimal("10.00"), OffsetDateTime.now());
        when(transactionService.confirm(TransactionType.WITHDRAWAL, key, request))
                .thenThrow(new DataIntegrityViolationException("uk_transactions_user_idempotency"));
        when(transactionService.replay(TransactionType.WITHDRAWAL, key, request)).thenReturn(Optional.of(winner));

        var response = controller.confirmTransaction(TransactionType.WITHDRAWAL, key, request);

        assertThat(response.getBody()).isSameAs(winner);
    }

    @Test
    void integrityViolationUnrelatedToKeyIsPropagated() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException("other constraint");
        when(transactionService.confirm(TransactionType.WITHDRAWAL, key, request)).thenThrow(violation);
        when(transactionService.replay(TransactionType.WITHDRAWAL, key, request)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.confirmTransaction(TransactionType.WITHDRAWAL, key, request))
                .isSameAs(violation);
    }
}
