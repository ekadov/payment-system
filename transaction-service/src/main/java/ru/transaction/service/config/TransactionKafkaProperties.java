package ru.transaction.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "transaction.kafka")
public record TransactionKafkaProperties(Topics topics, int partitions, Retry retry) {

    public record Topics(String depositRequested, String depositCompleted, String withdrawalRequested,
                         String withdrawalCompleted, String withdrawalFailed) {
    }

    public record Retry(int attempts, Duration interval) {
    }
}
