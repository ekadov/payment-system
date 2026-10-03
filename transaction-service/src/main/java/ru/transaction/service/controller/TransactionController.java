package ru.transaction.service.controller;

import lombok.AllArgsConstructor;
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

    @Override
    public ResponseEntity<TransactionInitResponse> initTransaction(TransactionType type, TransactionRequest request) {
        return ResponseEntity.ok(metrics.record("init", type, () -> transactionService.init(type, request)));
    }

    @Override
    public ResponseEntity<TransactionConfirmResponse> confirmTransaction(TransactionType type, TransactionRequest request) {
        return ResponseEntity.ok(metrics.record("confirm", type, () -> transactionService.confirm(type, request)));
    }

    @Override
    public ResponseEntity<TransactionStatusResponse> getTransactionStatus(UUID transactionId) {
        return ResponseEntity.ok(transactionService.getStatus(transactionId));
    }

    @Override
    public ResponseEntity<TransactionPageResponse> findTransactions(UUID userUid, UUID walletUid,
                                                                    TransactionType type, TransactionStatus status,
                                                                    OffsetDateTime dateFrom, OffsetDateTime dateTo,
                                                                    Integer page, Integer size) {
        return ResponseEntity.ok(transactionService.find(userUid, walletUid, type, status, dateFrom, dateTo,
                page == null ? 0 : page, size == null ? 20 : size));
    }
}
