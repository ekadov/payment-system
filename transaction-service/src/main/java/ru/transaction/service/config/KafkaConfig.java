package ru.transaction.service.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;
import ru.transaction.service.exception.BadRequestException;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.stream.Stream;

@Slf4j
@Configuration
public class KafkaConfig {

    public static final String DLT_SUFFIX = ".DLT";

    private static boolean isTransient(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException
                    || cause instanceof RecoverableDataAccessException
                    || cause instanceof CannotCreateTransactionException
                    || cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public KafkaAdmin.NewTopics transactionTopics(TransactionKafkaProperties properties) {
        TransactionKafkaProperties.Topics topics = properties.topics();
        return new KafkaAdmin.NewTopics(Stream.concat(
                        Stream.of(topics.depositRequested(), topics.depositCompleted(), topics.withdrawalRequested(),
                                topics.withdrawalCompleted(), topics.withdrawalFailed()),
                        topics.incoming().stream().map(topic -> topic + DLT_SUFFIX))
                .map(topic -> topic(topic, properties.partitions()))
                .toArray(NewTopic[]::new));
    }

    /**
     * <ul>
     *     <li>временная ошибка БД - повторы без ограничения числа попыток, партиция ждёт восстановления БД;</li>
     *     <li>невалидное сообщение - сразу в DLT;</li>
     *     <li>прочие ошибки - {@code retry.attempts} повторов, затем в DLT.</li>
     * </ul>
     * Offset сдвигается только после успешной записи в DLT. Повторный запуск из DLT - {@code DltReplayService}.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(TransactionKafkaProperties properties,
                                                KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> {
                    log.error("Kafka message sent to DLT: topic={}, partition={}, offset={}, key={}, reason={}",
                            record.topic(), record.partition(), record.offset(), record.key(), exception.getMessage());
                    return new TopicPartition(record.topic() + DLT_SUFFIX, record.partition());
                });
        TransactionKafkaProperties.Retry retry = properties.retry();
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer,
                new FixedBackOff(retry.interval().toMillis(), retry.attempts()));
        handler.setBackOffFunction((record, exception) -> isTransient(exception) ? transientBackOff(retry) : null);
        handler.addNotRetryableExceptions(JsonProcessingException.class, BadRequestException.class);
        handler.setCommitRecovered(true);
        return handler;
    }

    private BackOff transientBackOff(TransactionKafkaProperties.Retry retry) {
        ExponentialBackOff backOff = new ExponentialBackOff(retry.interval().toMillis(), 2.0);
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }

    private NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name).partitions(partitions).replicas(1).build();
    }
}
