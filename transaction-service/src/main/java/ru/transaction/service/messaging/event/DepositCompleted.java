package ru.transaction.service.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DepositCompleted {
    private UUID transactionId;
    private String status;
    private BigDecimal amount;
    private String failureReason;
    private Instant timestamp;
}
