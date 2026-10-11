package ru.transaction.service.client;

import io.micrometer.tracing.Tracer;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.transaction.service.config.OutboxProperties;
import ru.transaction.service.entity.OutboxEventEntity;
import ru.transaction.service.entity.OutboxStatus;
import ru.transaction.service.repository.OutboxEventsRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final OutboxProperties PROPERTIES = new OutboxProperties(true, 10, Duration.ofSeconds(1),
            Duration.ofSeconds(1), Duration.ofSeconds(30));

    @Mock
    private OutboxEventsRepository outboxEventsRepository;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;
    private OutboxRelay relay;

    private static OutboxEventEntity event() {
        return new OutboxEventEntity()
                .setUid(UUID.randomUUID())
                .setUserUid(UUID.randomUUID())
                .setTopic("withdrawal-requested")
                .setMessageKey(UUID.randomUUID().toString())
                .setPayload("{}")
                .setStatus(OutboxStatus.NEW)
                .setNextAttemptAt(Instant.now());
    }

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(outboxEventsRepository, kafkaTemplate,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), PROPERTIES, Tracer.NOOP);
    }

    @Test
    void eventIsMarkedSentOnlyAfterBrokerAcknowledgement() {
        OutboxEventEntity event = event();
        when(outboxEventsRepository.findReadyForUpdate(any(Instant.class), eq(10)))
                .thenReturn(List.of(event));
        when(kafkaTemplate.send("withdrawal-requested", event.getMessageKey(), "{}"))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay.publishBatch()).isEqualTo(1);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void failedSendKeepsEventForRetryAndStopsBatch() {
        OutboxEventEntity failed = event();
        OutboxEventEntity next = event();
        when(outboxEventsRepository.findReadyForUpdate(any(Instant.class), eq(10)))
                .thenReturn(List.of(failed, next));
        when(kafkaTemplate.send("withdrawal-requested", failed.getMessageKey(), "{}"))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker is not available")));
        Instant before = Instant.now();

        assertThat(relay.publishBatch()).isZero();

        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(1));
        assertThat(failed.getLastError()).contains("broker is not available");
        assertThat(next.getAttempts()).isZero();
        verify(kafkaTemplate, never()).send("withdrawal-requested", next.getMessageKey(), "{}");
    }

    @Test
    void retryDelayGrowsExponentiallyUpToLimit() {
        assertThat(relay.retryDelay(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(relay.retryDelay(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(relay.retryDelay(4)).isEqualTo(Duration.ofSeconds(8));
        assertThat(relay.retryDelay(10)).isEqualTo(Duration.ofSeconds(30));
        assertThat(relay.retryDelay(1000)).isEqualTo(Duration.ofSeconds(30));
    }
}
