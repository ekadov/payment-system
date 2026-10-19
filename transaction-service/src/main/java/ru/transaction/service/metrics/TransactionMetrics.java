package ru.transaction.service.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import ru.transaction.service.exception.BadRequestException;
import ru.transaction.service.exception.ConflictException;
import ru.transaction.service.exception.IdempotencyKeyReusedException;
import ru.transaction.service.exception.NotFoundException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;

@Component
@AllArgsConstructor
public class TransactionMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * Измеряет длительность операции вместе с коммитом транзакции и вычисляет результат:
     * success, rejected (4xx) или error.
     */
    public <T> T record(String operation, Enum<?> type, Supplier<T> action) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "error";
        try {
            T result = action.get();
            outcome = "success";
            return result;
        } catch (BadRequestException | NotFoundException | ConflictException | IdempotencyKeyReusedException exception) {
            outcome = "rejected";
            throw exception;
        } finally {
            sample.stop(Timer.builder("transactions.operation.duration")
                    .description("Длительность init/confirm транзакции")
                    .tag("operation", operation)
                    .tag("type", tag(type))
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            if (!"success".equals(outcome)) {
                meterRegistry.counter("transactions.failed.total",
                        "operation", operation, "type", tag(type), "outcome", outcome).increment();
            }
        }
    }

    public void initialized(Enum<?> type, boolean available) {
        meterRegistry.counter("transactions.init.total", "type", tag(type),
                "available", String.valueOf(available)).increment();
    }

    public void confirmed(Enum<?> type, String status) {
        meterRegistry.counter("transactions.confirm.total", "type", tag(type), "status", status).increment();
    }

    /**
     * Время от создания транзакции (confirm) до финализации по сообщению Kafka.
     */
    public void finalized(Enum<?> type, String status, Instant createdAt) {
        meterRegistry.counter("transactions.finalize.total", "type", tag(type), "status", status).increment();
        if (createdAt != null) {
            Timer.builder("transactions.finalize.latency")
                    .description("Время от confirm до финализации транзакции")
                    .tag("type", tag(type))
                    .tag("status", status)
                    .register(meterRegistry)
                    .record(Duration.between(createdAt, Instant.now()));
        }
    }

    private String tag(Enum<?> type) {
        return type == null ? "unknown" : type.name().toLowerCase(Locale.ROOT);
    }
}
