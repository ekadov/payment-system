package ru.transaction.service.client;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import ru.transaction.service.config.KafkaConfig;
import ru.transaction.service.config.TransactionKafkaProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Повторная отправка сообщений из DLT в исходные топики. Обрабатываются только сообщения,
 * лежавшие в DLT на момент вызова: если сообщение снова не обработается, оно вернётся в DLT
 * и будет ждать следующего запуска.
 * Идемпотентно, поэтому повторная доставка уже обработанного результата безопасна.
 */
@Slf4j
@Service
@AllArgsConstructor
public class DltReplayService {

    private static final String REPLAY_GROUP = "transaction-service-dlt-replay";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionKafkaProperties properties;

    /**
     * @return число переотправленных сообщений по каждому DLT
     */
    public Map<String, Integer> replayAll() {
        Map<String, Integer> replayed = new LinkedHashMap<>();
        for (String topic : properties.topics().incoming()) {
            String dlt = topic + KafkaConfig.DLT_SUFFIX;
            replayed.put(dlt, replay(dlt, topic));
        }
        return replayed;
    }

    private int replay(String dlt, String defaultTarget) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(REPLAY_GROUP, "dlt-replay")) {
            List<TopicPartition> partitions = consumer.partitionsFor(dlt).stream()
                    .map(info -> new TopicPartition(dlt, info.partition()))
                    .toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(new HashSet<>(partitions));
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata offset = committed.get(partition);
                if (offset == null) {
                    consumer.seekToBeginning(List.of(partition));
                } else {
                    consumer.seek(partition, offset.offset());
                }
            }
            int replayed = 0;
            Map<TopicPartition, Long> processed = new LinkedHashMap<>();
            for (TopicPartition partition : partitions) {
                processed.put(partition, consumer.position(partition));
            }
            while (partitions.stream().anyMatch(partition -> processed.get(partition) < endOffsets.get(partition))) {
                for (ConsumerRecord<String, String> record : consumer.poll(POLL_TIMEOUT)) {
                    TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                    if (record.offset() >= endOffsets.get(partition)) {
                        continue;
                    }
                    String target = originalTopic(record, defaultTarget);
                    send(target, record);
                    consumer.commitSync(Map.of(partition, new OffsetAndMetadata(record.offset() + 1)));
                    processed.put(partition, record.offset() + 1);
                    replayed++;
                    log.info("Kafka message replayed from DLT: dlt={}, offset={}, target={}, key={}",
                            dlt, record.offset(), target, record.key());
                }
            }
            return replayed;
        }
    }

    private void send(String topic, ConsumerRecord<String, String> record) {
        try {
            kafkaTemplate.send(topic, record.key(), record.value()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLT replay interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("DLT replay failed: topic=" + topic, exception);
        }
    }

    private String originalTopic(ConsumerRecord<String, String> record, String defaultTarget) {
        Header header = record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC);
        return header == null ? defaultTarget : new String(header.value(), StandardCharsets.UTF_8);
    }
}
