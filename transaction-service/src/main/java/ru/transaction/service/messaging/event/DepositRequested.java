package ru.transaction.service.messaging.event;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
public class DepositRequested {
    private UUID transactionId;
    private UUID userId;
    private UUID walletId;
    /**
     * Сумма к зачислению на кошелёк.
     */
    private BigDecimal amount;
    private BigDecimal fee;
    /**
     * Сумма к списанию провайдером с платёжного метода (amount + fee).
     */
    private BigDecimal totalAmount;
    private String currency;
    private Long paymentMethodId;
    private Instant timestamp;
}
