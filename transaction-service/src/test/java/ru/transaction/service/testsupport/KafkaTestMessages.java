package ru.transaction.service.testsupport;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

public final class KafkaTestMessages {

    private KafkaTestMessages() {
    }

    /**
     * Читает топик с начала новой группой и возвращает первое подходящее сообщение.
     */
    public static Optional<ConsumerRecord<String, String>> find(String topic,
                                                                Predicate<ConsumerRecord<String, String>> matcher,
                                                                Duration timeout) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (matcher.test(record)) {
                        return Optional.of(record);
                    }
                }
            }
            return Optional.empty();
        }
    }

    public static Optional<ConsumerRecord<String, String>> findByKey(String topic, Object key, Duration timeout) {
        return find(topic, record -> key.toString().equals(record.key()), timeout);
    }
}
