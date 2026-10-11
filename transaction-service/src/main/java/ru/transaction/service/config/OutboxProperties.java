package ru.transaction.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param relayEnabled     запускать ли отправку событий по расписанию
 * @param batchSize        сколько событий отправляется в одной транзакции
 * @param sendTimeout      сколько ждать подтверждения записи от Kafka
 * @param retryInterval    пауза перед первой повторной отправкой, дальше удваивается
 * @param maxRetryInterval предельная пауза между повторными отправками
 */
@ConfigurationProperties(prefix = "transaction.outbox")
public record OutboxProperties(boolean relayEnabled, int batchSize, Duration sendTimeout,
                               Duration retryInterval, Duration maxRetryInterval) {
}
