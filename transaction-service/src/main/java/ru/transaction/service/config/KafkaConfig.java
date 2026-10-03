package ru.transaction.service.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import ru.transaction.service.exception.BadRequestException;

@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    public KafkaAdmin.NewTopics transactionTopics(TransactionKafkaProperties properties) {
        TransactionKafkaProperties.Topics topics = properties.topics();
        return new KafkaAdmin.NewTopics(
                topic(topics.depositRequested(), properties.partitions()),
                topic(topics.depositCompleted(), properties.partitions()),
                topic(topics.withdrawalRequested(), properties.partitions()),
                topic(topics.withdrawalCompleted(), properties.partitions()),
                topic(topics.withdrawalFailed(), properties.partitions()));
    }

    /**
     * Временные ошибки (БД недоступна, транзакция ещё не найдена) повторяются с паузой.
     * Невалидные сообщения и сообщения, для которых попытки исчерпаны, логируются и пропускаются.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(TransactionKafkaProperties properties) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, exception) -> log.error("Kafka message skipped: topic={}, key={}, value={}, reason={}",
                        record.topic(), record.key(), record.value(), exception.getMessage()),
                new FixedBackOff(properties.retry().interval().toMillis(), properties.retry().attempts()));
        handler.addNotRetryableExceptions(JsonProcessingException.class, BadRequestException.class);
        return handler;
    }

    private NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
