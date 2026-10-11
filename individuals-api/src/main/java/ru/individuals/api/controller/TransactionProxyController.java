package ru.individuals.api.controller;

import feign.FeignException;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;
import lombok.AllArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.individuals.api.client.transaction.generated.api.TransactionsApi;
import ru.individuals.api.client.transaction.generated.api.WalletsApi;
import ru.individuals.api.client.transaction.generated.model.CreateWalletRequest;
import ru.individuals.api.client.transaction.generated.model.TransactionConfirmResponse;
import ru.individuals.api.client.transaction.generated.model.TransactionInitResponse;
import ru.individuals.api.client.transaction.generated.model.TransactionPageResponse;
import ru.individuals.api.client.transaction.generated.model.TransactionRequest;
import ru.individuals.api.client.transaction.generated.model.TransactionStatus;
import ru.individuals.api.client.transaction.generated.model.TransactionStatusResponse;
import ru.individuals.api.client.transaction.generated.model.TransactionType;
import ru.individuals.api.client.transaction.generated.model.WalletResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/v1")
@AllArgsConstructor
public class TransactionProxyController {

    private static final ContextSnapshotFactory CONTEXT_SNAPSHOT_FACTORY = ContextSnapshotFactory.builder().build();

    private final TransactionsApi transactionsApi;
    private final WalletsApi walletsApi;

    @PostMapping("/wallets")
    public Mono<ResponseEntity<WalletResponse>> createWallet(@AuthenticationPrincipal Jwt jwt,
                                                              @RequestBody CreateWalletRequest request) {
        requireCurrentUser(jwt, request.getUserUid());
        return blocking(() -> ResponseEntity.status(201).body(walletsApi.createWallet(request)));
    }

    @GetMapping("/wallets/{walletUid}")
    public Mono<WalletResponse> getWallet(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID walletUid) {
        return blocking(() -> {
            WalletResponse wallet = walletsApi.getWallet(walletUid);
            requireCurrentUser(jwt, wallet.getUserUid());
            return wallet;
        });
    }

    @GetMapping("/wallets/user/{userUid}")
    public Mono<List<WalletResponse>> getUserWallets(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userUid) {
        requireCurrentUser(jwt, userUid);
        return blocking(() -> walletsApi.getUserWallets(userUid));
    }

    @PostMapping("/transactions/{type}/init")
    public Mono<TransactionInitResponse> init(@AuthenticationPrincipal Jwt jwt, @PathVariable TransactionType type,
                                               @RequestBody TransactionRequest request) {
        requireCurrentUser(jwt, request.getUserUid());
        return blocking(() -> transactionsApi.initTransaction(type, request));
    }

    @PostMapping("/transactions/{type}/confirm")
    public Mono<TransactionConfirmResponse> confirm(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable TransactionType type,
                                                     @RequestBody TransactionRequest request) {
        requireCurrentUser(jwt, request.getUserUid());
        return blocking(() -> transactionsApi.confirmTransaction(type, request));
    }

    @GetMapping("/transactions/{transactionId}/status")
    public Mono<TransactionStatusResponse> status(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID transactionId) {
        return blocking(() -> {
            TransactionStatusResponse response = transactionsApi.getTransactionStatus(transactionId);
            requireCurrentUser(jwt, response.getUserUid());
            return response;
        });
    }

    @GetMapping("/transactions")
    public Mono<TransactionPageResponse> find(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) UUID walletUid,
                                               @RequestParam(required = false) TransactionType type,
                                               @RequestParam(required = false) TransactionStatus status,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dateFrom,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime dateTo,
                                               @RequestParam(defaultValue = "0") Integer page,
                                               @RequestParam(defaultValue = "20") Integer size) {
        UUID userUid = currentUser(jwt);
        return blocking(() -> transactionsApi.findTransactions(userUid, walletUid, type, status,
                dateFrom, dateTo, page, size));
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<String> downstreamError(FeignException exception) {
        int status = exception.status() > 0 ? exception.status() : 502;
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(exception.contentUTF8());
    }

    private UUID currentUser(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(FORBIDDEN, "JWT is not an UUID");
        }
    }

    private void requireCurrentUser(Jwt jwt, UUID requestedUserUid) {
        if (!currentUser(jwt).equals(requestedUserUid)) {
            throw new ResponseStatusException(FORBIDDEN, "Operation for another user is forbidden");
        }
    }

    private <T> Mono<T> blocking(java.util.concurrent.Callable<T> operation) {
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
            try (ContextSnapshot.Scope ignored = CONTEXT_SNAPSHOT_FACTORY.setThreadLocalsFrom(context)) {
                return operation.call();
            }
        })).subscribeOn(Schedulers.boundedElastic());
    }
}
