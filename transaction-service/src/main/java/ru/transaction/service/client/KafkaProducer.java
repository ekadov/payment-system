package ru.transaction.service.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.transaction.service.messaging.event.OutgoingEvent;

@Slf4j
@Service
@AllArgsConstructor
public class KafkaProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Отправка только после коммита.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void send(OutgoingEvent event) throws JsonProcessingException {
        String payload = objectMapper.writeValueAsString(event.payload());
        kafkaTemplate.send(event.topic(), event.key(), payload).whenComplete((result, exception) -> {
            if (exception != null) {
                log.error("Kafka send failed: topic={}, key={}", event.topic(), event.key(), exception);
            } else {
                log.info("Kafka event sent: topic={}, key={}", event.topic(), event.key());
            }
        });
    }
}
