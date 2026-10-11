package ru.transaction.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "transaction.kafka")
public record TransactionKafkaProperties(Topics topics, int partitions, Retry retry) {

    public record Topics(String depositRequested, String depositCompleted, String withdrawalRequested,
                         String withdrawalCompleted, String withdrawalFailed) {

        /**
         * Топики с ответами провайдера: у каждого есть DLT.
         */
        public List<String> incoming() {
            return List.of(depositCompleted, withdrawalCompleted, withdrawalFailed);
        }
    }

    /**
     * @param attempts    число повторов для нетранзиентных ошибок перед отправкой в DLT
     * @param interval    пауза между повторами (начальная - для временных ошибок БД)
     * @param maxInterval предельная пауза между повторами при временных ошибках БД
     */
    public record Retry(int attempts, Duration interval, Duration maxInterval) {
    }
}
