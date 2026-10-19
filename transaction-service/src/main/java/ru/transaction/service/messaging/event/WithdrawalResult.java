package ru.transaction.service.messaging.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * WithdrawalCompleted / WithdrawalFailed.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WithdrawalResult {
    private UUID transactionId;
    private String status;
    private String failureReason;
    private Instant timestamp;
}
