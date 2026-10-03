package ru.transaction.service.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import ru.transaction.service.config.TransactionKafkaProperties;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.messaging.event.DepositCompleted;
import ru.transaction.service.messaging.event.WithdrawalResult;
import ru.transaction.service.service.TransactionService;

@Slf4j
@Service
@AllArgsConstructor
public class KafkaConsumer {

    private final TransactionService transactionService;
    private final ObjectMapper objectMapper;
    private final TransactionKafkaProperties properties;

    @KafkaListener(topics = "${transaction.kafka.topics.deposit-completed}", groupId = "transaction-service-deposit")
    public void listenDeposit(String payload, Acknowledgment acknowledgment) throws JsonProcessingException {
        DepositCompleted event = objectMapper.readValue(payload, DepositCompleted.class);
        requireTransactionId(event.getTransactionId());
        log.info("DepositCompleted received: transactionId={}, status={}", event.getTransactionId(), event.getStatus());
        transactionService.finalizeDeposit(event.getTransactionId(), event.getStatus(), event.getAmount(),
                event.getFailureReason());
        acknowledgment.acknowledge();
    }

    @KafkaListener(topics = {"${transaction.kafka.topics.withdrawal-completed}",
            "${transaction.kafka.topics.withdrawal-failed}"}, groupId = "transaction-service-withdrawal")
    public void listenWithdrawal(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                 Acknowledgment acknowledgment) throws JsonProcessingException {
        WithdrawalResult event = objectMapper.readValue(payload, WithdrawalResult.class);
        requireTransactionId(event.getTransactionId());
        String status = event.getStatus() != null ? event.getStatus() : statusByTopic(topic);
        log.info("Withdrawal result received: topic={}, transactionId={}, status={}",
                topic, event.getTransactionId(), status);
        transactionService.finalizeWithdrawal(event.getTransactionId(), status, event.getFailureReason());
        acknowledgment.acknowledge();
    }

    private String statusByTopic(String topic) {
        return properties.topics().withdrawalFailed().equals(topic) ? "FAILED" : "COMPLETED";
    }

    private void requireTransactionId(Object transactionId) {
        if (transactionId == null) {
            throw new BadRequestException("transactionId is required");
        }
    }
}
