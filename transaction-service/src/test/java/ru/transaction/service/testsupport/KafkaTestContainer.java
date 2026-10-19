package ru.transaction.service.testsupport;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Один брокер Kafka на все интеграционные тесты.
 */
public final class KafkaTestContainer {

    public static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    static {
        KAFKA.start();
    }

    private KafkaTestContainer() {
    }
}
