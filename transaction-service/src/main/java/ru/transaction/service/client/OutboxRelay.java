package ru.transaction.service.client;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ru.transaction.service.config.OutboxProperties;
import ru.transaction.service.entity.OutboxEventEntity;
import ru.transaction.service.entity.OutboxStatus;
import ru.transaction.service.repository.OutboxEventsRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Отправляет события из outbox в Kafka. Событие помечается SENT только после подтверждения от брокера,
 * иначе остаётся NEW и отправляется повторно с нарастающей паузой - в том числе после перезапуска сервиса.
 * Гарантия доставки - at-least-once: получатель отсекает дубли по ключу (transactionId).
 */
@Slf4j
@Component
@AllArgsConstructor
@ConditionalOnProperty(prefix = "transaction.outbox", name = "relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelay {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final OutboxEventsRepository outboxEventsRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final Tracer tracer;

    @Scheduled(fixedDelayString = "${transaction.outbox.poll-interval}")
    public void publishPending() {
        Integer sent;
        do {
            sent = transactionTemplate.execute(status -> publishBatch());
        } while (sent != null && sent == properties.batchSize());
    }

    /**
     * @return число отправленных событий; меньше размера пачки - значит, готовых событий больше нет
     * или брокер недоступен
     */
    int publishBatch() {
        List<OutboxEventEntity> events = outboxEventsRepository.findReadyForUpdate(Instant.now(),
                properties.batchSize());
        int sent = 0;
        for (OutboxEventEntity event : events) {
            try {
                send(event);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                scheduleRetry(event, exception);
                break;
            } catch (Exception exception) {
                scheduleRetry(event, exception);
                // Скорее всего, брокер недоступен: остальные события подождут следующего запуска.
                break;
            }
            event.setStatus(OutboxStatus.SENT).setSentAt(Instant.now()).setAttempts(event.getAttempts() + 1)
                    .setLastError(null);
            sent++;
            log.info("Outbox event sent: topic={}, key={}", event.getTopic(), event.getMessageKey());
        }
        return sent;
    }

    private void send(OutboxEventEntity event) throws Exception {
        Span span = startSpan(event);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            span.error(exception);
            throw exception;
        } finally {
            span.end();
        }
    }

    private Span startSpan(OutboxEventEntity event) {
        Span.Builder builder = tracer.spanBuilder().name("outbox publish " + event.getTopic());
        if (event.getTraceId() != null && event.getSpanId() != null) {
            TraceContext parent = tracer.traceContextBuilder()
                    .traceId(event.getTraceId())
                    .spanId(event.getSpanId())
                    .sampled(true)
                    .build();
            builder.setParent(parent);
        }
        return builder.start();
    }

    private void scheduleRetry(OutboxEventEntity event, Exception exception) {
        int attempts = event.getAttempts() + 1;
        Duration delay = retryDelay(attempts);
        String error = String.valueOf(exception.getMessage());
        event.setAttempts(attempts)
                .setNextAttemptAt(Instant.now().plus(delay))
                .setLastError(error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error);
        log.warn("Outbox event send failed, retry in {}: topic={}, key={}, attempts={}",
                delay, event.getTopic(), event.getMessageKey(), attempts, exception);
    }

    Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(attempts - 1, 20);
        Duration delay = properties.retryInterval().multipliedBy(multiplier);
        return delay.compareTo(properties.maxRetryInterval()) > 0 ? properties.maxRetryInterval() : delay;
    }
}
