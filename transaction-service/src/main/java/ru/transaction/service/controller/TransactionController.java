package ru.transaction.service.controller;

import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import ru.transaction.service.generated.api.TransactionsApiDelegate;
import ru.transaction.service.generated.dto.*;
import ru.transaction.service.metrics.TransactionMetrics;
import ru.transaction.service.service.TransactionService;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@AllArgsConstructor
public class TransactionController implements TransactionsApiDelegate {

    private final TransactionService transactionService;
    private final TransactionMetrics metrics;
    private final CurrentUser currentUser;

    @Override
    public ResponseEntity<TransactionInitResponse> initTransaction(TransactionType type, TransactionRequest request) {
        currentUser.requireOwner(request.getUserUid());
        return ResponseEntity.ok(metrics.record("init", type, () -> transactionService.init(type, request)));
    }

    @Override
    public ResponseEntity<TransactionConfirmResponse> confirmTransaction(TransactionType type, UUID idempotencyKey,
                                                                         TransactionRequest request) {
        currentUser.requireOwner(request.getUserUid());
        return ResponseEntity.ok(metrics.record("confirm", type, () -> confirmOnce(type, idempotencyKey, request)));
    }

    private TransactionConfirmResponse confirmOnce(TransactionType type, UUID idempotencyKey, TransactionRequest request) {
        try {
            return transactionService.confirm(type, idempotencyKey, request);
        } catch (DataIntegrityViolationException exception) {
            return transactionService.replay(type, idempotencyKey, request).orElseThrow(() -> exception);
        }
    }

    @Override
    public ResponseEntity<TransactionStatusResponse> getTransactionStatus(UUID transactionId) {
        TransactionStatusResponse status = transactionService.getStatus(transactionId);
        currentUser.requireOwner(status.getUserUid());
        return ResponseEntity.ok(status);
    }

    @Override
    public ResponseEntity<TransactionPageResponse> findTransactions(UUID userUid, UUID walletUid,
                                                                    TransactionType type, TransactionStatus status,
                                                                    OffsetDateTime dateFrom, OffsetDateTime dateTo,
                                                                    Integer page, Integer size) {
        UUID owner = currentUser.id();
        if (userUid != null) {
            currentUser.requireOwner(userUid);
        }
        return ResponseEntity.ok(transactionService.find(owner, walletUid, type, status, dateFrom, dateTo,
                page == null ? 0 : page, size == null ? 20 : size));
    }
}
