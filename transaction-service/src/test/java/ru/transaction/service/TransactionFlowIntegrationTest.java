package ru.transaction.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.transaction.service.repository.TransactionsRepository;
import ru.transaction.service.testsupport.KafkaTestContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static ru.transaction.service.testsupport.TestData.*;

/**
 * Полный цикл через HTTP API и Kafka: init -> confirm -> *Requested -> ответ провайдера -> finalize.
 * Роль внешнего платёжного провайдера играет сам тест: читает *-requested и отправляет ответ.
 */
@SpringBootTest(properties = {
        "transaction.kafka.retry.attempts=1",
        "transaction.kafka.retry.interval=100ms",
        "management.otlp.tracing.export.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext
class TransactionFlowIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("transaction_service");
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(30);
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TransactionsRepository transactionsRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=transaction");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
    }

    @Test
    void depositIsCalculatedOnInitAndCompletedByProviderResponse() throws Exception {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = createWallet(userUid, "deposit");
        long transactionsBefore = transactionsRepository.count();

        perform(userUid, post("/transactions/deposit/init"), depositJson(userUid, walletUid, "100.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fee").value(1.0))
                .andExpect(jsonPath("$.totalAmount").value(101.0))
                .andExpect(jsonPath("$.available").value(true));
        assertThat(transactionsRepository.count()).as("init не создаёт записей").isEqualTo(transactionsBefore);

        UUID transactionId = confirm(userUid, "deposit", depositJson(userUid, walletUid, "100.00"), "PENDING");

        JsonNode requested = awaitRequested("deposit-requested", transactionId);
        assertThat(requested.get("walletId").asText()).isEqualTo(walletUid.toString());
        assertThat(requested.get("amount").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(requested.get("totalAmount").decimalValue()).isEqualByComparingTo("101.00");
        assertThat(balance(userUid, walletUid)).as("до ответа провайдера баланс не меняется").isEqualByComparingTo("0.00");

        provider("deposit-completed", transactionId, "{\"transactionId\":\"%s\",\"status\":\"COMPLETED\",\"amount\":100.00}");

        awaitStatus(userUid, transactionId, "COMPLETED");
        assertThat(balance(userUid, walletUid)).isEqualByComparingTo("100.00");
    }

    @Test
    void duplicateDepositCompletedDoesNotCreditTwice() throws Exception {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = createWallet(userUid, "duplicate");
        UUID transactionId = deposit(userUid, walletUid, "50.00");

        provider("deposit-completed", transactionId, "{\"transactionId\":\"%s\",\"status\":\"COMPLETED\",\"amount\":50.00}");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(balance(userUid, walletUid)).isEqualByComparingTo("50.00"));
    }

    @Test
    void withdrawalDebitsOnConfirmAndRefundsOnWithdrawalFailed() throws Exception {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = createWallet(userUid, "withdrawal");
        deposit(userUid, walletUid, "200.00");

        UUID failed = confirm(userUid, "withdrawal", withdrawalJson(userUid, walletUid, "100.00"), "PENDING");
        assertThat(balance(userUid, walletUid)).as("сумма и комиссия списаны сразу при confirm").isEqualByComparingTo("99.00");
        assertThat(awaitRequested("withdrawal-requested", failed).get("destination").asText())
                .isEqualTo("Visa **** 1234");
        provider("withdrawal-failed", failed,
                "{\"transactionId\":\"%s\",\"status\":\"FAILED\",\"failureReason\":\"INSUFFICIENT_FUNDS_EXTERNAL\"}");
        awaitStatus(userUid, failed, "FAILED");
        assertThat(balance(userUid, walletUid)).isEqualByComparingTo("200.00");
        perform(userUid, get("/transactions/{id}/status", failed), null)
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS_EXTERNAL"));

        UUID completed = confirm(userUid, "withdrawal", withdrawalJson(userUid, walletUid, "50.00"), "PENDING");
        awaitRequested("withdrawal-requested", completed);
        // Статус в сообщении не указан - он определяется по топику withdrawal-completed.
        provider("withdrawal-completed", completed, "{\"transactionId\":\"%s\"}");
        awaitStatus(userUid, completed, "COMPLETED");
        assertThat(balance(userUid, walletUid)).isEqualByComparingTo("149.50");
    }

    @Test
    void transferCompletesSynchronously() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        UUID source = createWallet(sender, "source");
        UUID target = createWallet(recipient, "target");
        deposit(sender, source, "100.00");

        confirm(sender, "transfer", transferJson(sender, source, target, "40.00"), "COMPLETED");

        assertThat(balance(sender, source)).isEqualByComparingTo("59.80");
        assertThat(balance(recipient, target)).isEqualByComparingTo("40.00");
    }

    /**
     * Коммит прошёл, ответ потерян: повтор с тем же ключом возвращает ту же транзакцию без второго списания.
     */
    @Test
    void retriedConfirmWithSameKeyDoesNotDebitTwice() throws Exception {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = createWallet(userUid, "idempotent");
        deposit(userUid, walletUid, "100.00");
        UUID key = UUID.randomUUID();
        String body = withdrawalJson(userUid, walletUid, "50.00");

        UUID first = UUID.fromString(read(confirmRequest(userUid, "withdrawal", key, body)
                .andExpect(status().isOk())).get("transactionId").asText());
        UUID retry = UUID.fromString(read(confirmRequest(userUid, "withdrawal", key, body)
                .andExpect(status().isOk())).get("transactionId").asText());

        assertThat(retry).isEqualTo(first);
        assertThat(balance(userUid, walletUid)).isEqualByComparingTo("49.50");
        confirmRequest(userUid, "withdrawal", key, withdrawalJson(userUid, walletUid, "10.00"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(balance(userUid, walletUid)).isEqualByComparingTo("49.50");
    }

    /**
     * Повтор пришёл, пока первый запрос ещё выполняется: списание всё равно одно.
     */
    @Test
    void concurrentConfirmsWithSameKeyDebitOnce() throws Exception {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = createWallet(userUid, "concurrent");
        deposit(userUid, walletUid, "100.00");
        UUID key = UUID.randomUUID();
        String body = withdrawalJson(userUid, walletUid, "10.00");

        List<CompletableFuture<String>> requests = IntStream.range(0, 4)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return read(confirmRequest(userUid, "withdrawal", key, body).andExpect(status().isOk()))
                                .get("transactionId").asText();
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }))
                .toList();

        assertThat(requests.stream().map(CompletableFuture::join).distinct()).hasSize(1);
        assertThat(balance(userUid, walletUid)).isEqualByComparingTo("89.90");
    }

    @Test
    void validationAndBusinessErrorsAreReturnedAsProblemJson() throws Exception {
        UUID userUid = UUID.randomUUID();
        UUID walletUid = createWallet(userUid, "errors");

        perform(userUid, post("/transactions/deposit/init"), depositJson(userUid, walletUid, "0.001"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        confirmRequest(userUid, "withdrawal", UUID.randomUUID(), withdrawalJson(userUid, walletUid, "10.00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Insufficient funds"));
        perform(userUid, post("/wallets"), createWalletJson(userUid, USD_WALLET_TYPE, "errors"))
                .andExpect(status().isConflict());
        perform(userUid, get("/transactions/{id}/status", UUID.randomUUID()), null)
                .andExpect(status().isNotFound());
    }

    /**
     * Пополнение, подтверждённое провайдером.
     */
    private UUID deposit(UUID userUid, UUID walletUid, String amount) throws Exception {
        UUID transactionId = confirm(userUid, "deposit", depositJson(userUid, walletUid, amount), "PENDING");
        awaitRequested("deposit-requested", transactionId);
        provider("deposit-completed", transactionId,
                "{\"transactionId\":\"%s\",\"status\":\"COMPLETED\",\"amount\":" + amount + "}");
        awaitStatus(userUid, transactionId, "COMPLETED");
        return transactionId;
    }

    /**
     * Ответ провайдера: шаблон сообщения с одним %s для transactionId.
     */
    private void provider(String topic, UUID transactionId, String messageTemplate) throws Exception {
        kafkaTemplate.send(topic, transactionId.toString(), messageTemplate.formatted(transactionId))
                .get(10, TimeUnit.SECONDS);
    }

    /**
     * Ждёт событие *Requested, которое сервис отправляет после коммита confirm.
     */
    private JsonNode awaitRequested(String topic, UUID transactionId) {
        AtomicReference<String> payload = new AtomicReference<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            await().atMost(ASYNC_TIMEOUT).until(() -> {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (transactionId.toString().equals(record.key())) {
                        payload.set(record.value());
                    }
                }
                return payload.get() != null;
            });
        }
        try {
            return objectMapper.readTree(payload.get());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID createWallet(UUID userUid, String name) throws Exception {
        JsonNode wallet = read(perform(userUid, post("/wallets"), createWalletJson(userUid, USD_WALLET_TYPE, name))
                .andExpect(status().isCreated()));
        return UUID.fromString(wallet.get("uid").asText());
    }

    private UUID confirm(UUID caller, String type, String body, String expectedStatus) throws Exception {
        JsonNode response = read(confirmRequest(caller, type, UUID.randomUUID(), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus)));
        return UUID.fromString(response.get("transactionId").asText());
    }

    private ResultActions confirmRequest(UUID caller, String type, UUID idempotencyKey, String body) throws Exception {
        return perform(caller, post("/transactions/{type}/confirm", type)
                .header("Idempotency-Key", idempotencyKey.toString()), body);
    }

    private void awaitStatus(UUID caller, UUID transactionId, String expected) {
        await().atMost(ASYNC_TIMEOUT).untilAsserted(() ->
                perform(caller, get("/transactions/{id}/status", transactionId), null)
                        .andExpect(jsonPath("$.status").value(expected)));
    }

    private BigDecimal balance(UUID caller, UUID walletUid) throws Exception {
        return read(perform(caller, get("/wallets/{id}", walletUid), null).andExpect(status().isOk()))
                .get("balance").decimalValue();
    }

    /**
     * Запрос от имени пользователя: JWT, который в рабочей схеме пересылает шлюз.
     */
    private ResultActions perform(UUID caller, MockHttpServletRequestBuilder request, String body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request.with(jwt().jwt(token -> token.subject(caller.toString())))
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
