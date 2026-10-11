package ru.transaction.service;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.transaction.service.generated.dto.CreateWalletRequest;
import ru.transaction.service.generated.dto.TransactionRequest;
import ru.transaction.service.generated.dto.TransactionStatus;
import ru.transaction.service.generated.dto.TransactionType;
import ru.transaction.service.service.TransactionService;
import ru.transaction.service.service.WalletService;
import ru.transaction.service.testsupport.KafkaTestContainer;
import ru.transaction.service.testsupport.KafkaTestMessages;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static ru.transaction.service.testsupport.TestData.USD_WALLET_TYPE;

/**
 * Результаты платежей не теряются: временные ошибки БД повторяются до успеха,
 * остальные после исчерпания попыток попадают в DLT и могут быть переиграны.
 */
@SpringBootTest(properties = {
        "transaction.kafka.retry.attempts=1",
        "transaction.kafka.retry.interval=100ms",
        "transaction.kafka.retry.max-interval=200ms",
        "management.otlp.tracing.export.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
class KafkaErrorHandlingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("transaction_service");
    private static final String WITHDRAWAL_COMPLETED = "withdrawal-completed";
    private static final String WITHDRAWAL_COMPLETED_DLT = "withdrawal-completed.DLT";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    @MockitoSpyBean
    private TransactionService transactionService;
    @Autowired
    private WalletService walletService;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=transaction");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
    }

    private static String completed(UUID transactionId) {
        return "{\"transactionId\":\"%s\",\"status\":\"COMPLETED\"}".formatted(transactionId);
    }

    /**
     * Сценарий: первый результат в партиции падает на временной ошибке БД дольше, чем длится
     * обычный лимит повторов, следующий за ним - успешен.
     */
    @Test
    void transientDatabaseErrorIsRetriedUntilSuccessAndNextMessageDoesNotOvertakeIt() throws Exception {
        UUID first = pendingWithdrawal();
        UUID second = pendingWithdrawal();
        CannotCreateTransactionException databaseDown = new CannotCreateTransactionException("database is unavailable");
        doThrow(databaseDown).doThrow(databaseDown).doThrow(databaseDown).doThrow(databaseDown).doThrow(databaseDown)
                .doCallRealMethod()
                .when(transactionService).finalizeWithdrawal(eq(first), any(), any());

        sendToPartition(0, first, completed(first));
        sendToPartition(0, second, completed(second));

        awaitStatus(first, TransactionStatus.COMPLETED);
        awaitStatus(second, TransactionStatus.COMPLETED);
        verify(transactionService, atLeast(6)).finalizeWithdrawal(eq(first), any(), any());
        assertThat(KafkaTestMessages.find(WITHDRAWAL_COMPLETED_DLT, record -> first.toString().equals(record.key()),
                Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void invalidMessageGoesToDltAndDoesNotBlockPartition() throws Exception {
        String key = "invalid-" + UUID.randomUUID();
        UUID next = pendingWithdrawal();

        sendToPartition(1, key, "not a json");
        sendToPartition(1, next, completed(next));

        awaitStatus(next, TransactionStatus.COMPLETED);
        ConsumerRecord<String, String> dead = KafkaTestMessages.find(WITHDRAWAL_COMPLETED_DLT,
                record -> key.equals(record.key()), ASYNC_TIMEOUT).orElseThrow();
        assertThat(dead.value()).isEqualTo("not a json");
        assertThat(dead.partition()).isEqualTo(1);
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
    }

    @Test
    void messageFromDltIsProcessedAfterReplay() throws Exception {
        UUID transactionId = pendingWithdrawal();
        doThrow(new IllegalStateException("unexpected failure"))
                .when(transactionService).finalizeWithdrawal(eq(transactionId), any(), any());

        sendToPartition(2, transactionId, completed(transactionId));
        KafkaTestMessages.find(WITHDRAWAL_COMPLETED_DLT, record -> transactionId.toString().equals(record.key()), ASYNC_TIMEOUT)
                .orElseThrow();
        assertThat(transactionService.getStatus(transactionId).getStatus()).isEqualTo(TransactionStatus.PENDING);

        doCallRealMethod().when(transactionService).finalizeWithdrawal(eq(transactionId), any(), any());
        mockMvc.perform(post("/actuator/dltreplay")).andExpect(status().isOk());

        awaitStatus(transactionId, TransactionStatus.COMPLETED);
    }

    private UUID pendingWithdrawal() {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = walletService.createWallet(new CreateWalletRequest(userUid, USD_WALLET_TYPE, "main")).getUid();
        jdbcTemplate.update("update transaction.wallets set balance = 100.00 where uid = ?", walletUid);
        return transactionService.confirm(TransactionType.WITHDRAWAL,
                        new TransactionRequest(userUid, walletUid, new BigDecimal("10.00"))
                                .currency("USD").paymentMethodId(1L))
                .getTransactionId();
    }

    private void sendToPartition(int partition, Object key, String value) throws Exception {
        kafkaTemplate.send(WITHDRAWAL_COMPLETED, partition, key.toString(), value).get(10, TimeUnit.SECONDS);
    }

    private void awaitStatus(UUID transactionId, TransactionStatus expected) {
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                assertThat(transactionService.getStatus(transactionId).getStatus()).isEqualTo(expected));
    }
}
