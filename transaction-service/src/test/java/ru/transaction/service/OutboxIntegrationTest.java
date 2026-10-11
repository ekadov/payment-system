package ru.transaction.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.transaction.service.entity.OutboxEventEntity;
import ru.transaction.service.entity.OutboxStatus;
import ru.transaction.service.generated.dto.CreateWalletRequest;
import ru.transaction.service.generated.dto.TransactionRequest;
import ru.transaction.service.generated.dto.TransactionStatus;
import ru.transaction.service.generated.dto.TransactionType;
import ru.transaction.service.repository.OutboxEventsRepository;
import ru.transaction.service.service.TransactionService;
import ru.transaction.service.service.WalletService;
import ru.transaction.service.testsupport.KafkaTestContainer;
import ru.transaction.service.testsupport.KafkaTestMessages;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static ru.transaction.service.testsupport.TestData.USD_WALLET_TYPE;

/**
 * Transactional outbox: событие withdrawal-requested не теряется, если Kafka недоступна
 * или процесс остановился до отправки. Приложение поднимается вручную, чтобы проверить перезапуск.
 */
@Testcontainers
class OutboxIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("transaction_service");
    private static final String WITHDRAWAL_REQUESTED = "withdrawal-requested";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(60);

    private static ConfigurableApplicationContext start(boolean relayEnabled) {
        return new SpringApplicationBuilder(TransactionServiceApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl() + "&currentSchema=transaction",
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KafkaTestContainer.KAFKA.getBootstrapServers(),
                "--spring.kafka.listener.auto-startup=false",
                "--spring.kafka.producer.properties.max.block.ms=2000",
                "--spring.kafka.producer.properties.request.timeout.ms=1000",
                "--spring.kafka.producer.properties.delivery.timeout.ms=3000",
                "--transaction.outbox.relay-enabled=" + relayEnabled,
                "--transaction.outbox.poll-interval=PT0.2S",
                "--transaction.outbox.send-timeout=5s",
                "--transaction.outbox.retry-interval=200ms",
                "--transaction.outbox.max-retry-interval=1s",
                "--management.otlp.tracing.export.enabled=false");
    }

    private static UUID pendingWithdrawal(ConfigurableApplicationContext context) {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = context.getBean(WalletService.class)
                .createWallet(new CreateWalletRequest(userUid, USD_WALLET_TYPE, "main")).getUid();
        context.getBean(JdbcTemplate.class)
                .update("update transaction.wallets set balance = 100.00 where uid = ?", walletUid);
        return context.getBean(TransactionService.class).confirm(TransactionType.WITHDRAWAL,
                        new TransactionRequest(userUid, walletUid, new BigDecimal("10.00"))
                                .currency("USD").paymentMethodId(1L))
                .getTransactionId();
    }

    private static BigDecimal balanceOfWalletFor(ConfigurableApplicationContext context, UUID transactionId) {
        return context.getBean(JdbcTemplate.class).queryForObject("""
                select wallet.balance from transaction.wallets wallet
                join transaction.transactions transaction on transaction.wallet_uid = wallet.uid
                where transaction.uid = ?""", BigDecimal.class, transactionId);
    }

    private static OutboxEventEntity outboxEvent(ConfigurableApplicationContext context, UUID transactionId) {
        return context.getBean(OutboxEventsRepository.class).findByMessageKey(transactionId.toString())
                .stream().findFirst().orElseThrow();
    }

    private static void pauseKafka() {
        KafkaTestContainer.KAFKA.getDockerClient()
                .pauseContainerCmd(KafkaTestContainer.KAFKA.getContainerId()).exec();
    }

    private static void unpauseKafka() {
        KafkaTestContainer.KAFKA.getDockerClient()
                .unpauseContainerCmd(KafkaTestContainer.KAFKA.getContainerId()).exec();
    }

    @Test
    void eventIsDeliveredAfterKafkaBecomesAvailable() {
        try (ConfigurableApplicationContext context = start(true)) {
            // Kafka пропадает во время работы: продюсер уже подключён и знает кластер.
            UUID warmUp = pendingWithdrawal(context);
            await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                    assertThat(outboxEvent(context, warmUp).getStatus()).isEqualTo(OutboxStatus.SENT));

            pauseKafka();
            UUID transactionId;
            try {
                transactionId = pendingWithdrawal(context);

                assertThat(balanceOfWalletFor(context, transactionId)).isEqualByComparingTo("89.90");
                await().atMost(ASYNC_TIMEOUT).untilAsserted(() -> {
                    OutboxEventEntity event = outboxEvent(context, transactionId);
                    assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
                    assertThat(event.getAttempts()).isPositive();
                    assertThat(event.getLastError()).isNotBlank();
                });
            } finally {
                unpauseKafka();
            }

            await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                    assertThat(outboxEvent(context, transactionId).getStatus()).isEqualTo(OutboxStatus.SENT));
            assertThat(KafkaTestMessages.findByKey(WITHDRAWAL_REQUESTED, transactionId, ASYNC_TIMEOUT)).isPresent();
            assertThat(context.getBean(TransactionService.class).getStatus(transactionId).getStatus())
                    .isEqualTo(TransactionStatus.PENDING);
        }
    }

    @Test
    void eventSavedBeforeShutdownIsDeliveredAfterRestart() {
        UUID transactionId;
        try (ConfigurableApplicationContext beforeShutdown = start(false)) {
            transactionId = pendingWithdrawal(beforeShutdown);
            assertThat(outboxEvent(beforeShutdown, transactionId).getStatus()).isEqualTo(OutboxStatus.NEW);
        }
        assertThat(KafkaTestMessages.findByKey(WITHDRAWAL_REQUESTED, transactionId, Duration.ofSeconds(3))).isEmpty();

        try (ConfigurableApplicationContext afterRestart = start(true)) {
            await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                    assertThat(outboxEvent(afterRestart, transactionId).getStatus()).isEqualTo(OutboxStatus.SENT));
            assertThat(KafkaTestMessages.findByKey(WITHDRAWAL_REQUESTED, transactionId, ASYNC_TIMEOUT)).isPresent();
        }
    }
}
