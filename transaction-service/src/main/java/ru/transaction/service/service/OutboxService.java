package ru.transaction.service.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.transaction.service.entity.OutboxEventEntity;
import ru.transaction.service.entity.OutboxStatus;
import ru.transaction.service.repository.OutboxEventsRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@AllArgsConstructor
public class OutboxService {

    private final OutboxEventsRepository outboxEventsRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    /**
     * Сохраняет событие в той же транзакции, что и операция: либо зафиксированы оба, либо ни одно.
     * Вызов вне транзакции - ошибка (MANDATORY). Отправку в Kafka выполняет OutboxRelay.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String key, UUID userUid, Object payload) {
        OutboxEventEntity event = new OutboxEventEntity()
                .setUserUid(userUid)
                .setTopic(topic)
                .setMessageKey(key)
                .setPayload(toJson(payload))
                .setStatus(OutboxStatus.NEW)
                .setNextAttemptAt(Instant.now());
        // Контекст трассы запроса: relay продолжит её при отправке.
        Span span = tracer.currentSpan();
        if (span != null) {
            event.setTraceId(span.context().traceId()).setSpanId(span.context().spanId());
        }
        outboxEventsRepository.save(event);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize outbox event", exception);
        }
    }
}
