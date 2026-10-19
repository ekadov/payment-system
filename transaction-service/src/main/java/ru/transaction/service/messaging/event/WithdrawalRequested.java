package ru.transaction.service.messaging.event;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class WithdrawalRequested {
    private UUID transactionId;
    private UUID userId;
    private UUID walletId;
    private BigDecimal amount;
    private String currency;
    private Long paymentMethodId;
    private String destination;
    private Instant timestamp;
}
