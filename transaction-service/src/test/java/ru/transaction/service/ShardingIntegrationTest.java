package ru.transaction.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.transaction.service.exception.ConflictException;
import ru.transaction.service.generated.dto.CreateWalletRequest;
import ru.transaction.service.generated.dto.TransactionRequest;
import ru.transaction.service.generated.dto.TransactionType;
import ru.transaction.service.generated.dto.WalletResponse;
import ru.transaction.service.service.TransactionService;
import ru.transaction.service.service.WalletService;
import ru.transaction.service.testsupport.KafkaTestContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.transaction.service.testsupport.TestData.USD_WALLET_TYPE;

@SpringBootTest(properties = {
        "management.otlp.tracing.export.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@ActiveProfiles("sharding")
@Testcontainers
@DirtiesContext
class ShardingIntegrationTest {

    private static final String SCHEMA_SCRIPT = "migration/V1__init_transactions_schema.sql";

    @Container
    static final PostgreSQLContainer SHARD_0 = shard();
    @Container
    static final PostgreSQLContainer SHARD_1 = shard();
    @Autowired
    private WalletService walletService;
    @Autowired
    private TransactionService transactionService;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // ShardingSphere читает URL шардов из системных свойств (placeholder-type=system_props).
        System.setProperty("transaction.shard0.url", SHARD_0.getJdbcUrl() + "&currentSchema=transaction&stringtype=unspecified");
        System.setProperty("transaction.shard1.url", SHARD_1.getJdbcUrl() + "&currentSchema=transaction&stringtype=unspecified");
        registry.add("spring.kafka.bootstrap-servers", KafkaTestContainer.KAFKA::getBootstrapServers);
    }

    private static PostgreSQLContainer shard() {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("transaction_service")
                .withUsername("postgres")
                .withPassword("postgres")
                .withInitScript(SCHEMA_SCRIPT)
                .withCommand("postgres", "-c", "max_prepared_transactions=100");
    }

    @Test
    void walletsAreDistributedAcrossShardsByUser() throws Exception {
        List<UUID> users = IntStream.range(0, 20).mapToObj(i -> UUID.randomUUID()).toList();
        for (UUID user : users) {
            walletService.createWallet(new CreateWalletRequest(user, USD_WALLET_TYPE, "main"));
        }

        List<UUID> onShard0 = usersOnShard(SHARD_0, users);
        List<UUID> onShard1 = usersOnShard(SHARD_1, users);

        assertThat(onShard0).isNotEmpty().doesNotContainAnyElementsOf(onShard1);
        assertThat(onShard1).isNotEmpty();
        assertThat(onShard0.size() + onShard1.size()).isEqualTo(users.size());
        users.forEach(user -> assertThat(walletService.getByUserUid(user)).hasSize(1));
    }

    @Test
    void crossShardTransferIsAppliedToBothShards() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = userOnOtherShardThan(sender);
        WalletResponse source = walletService.createWallet(new CreateWalletRequest(sender, USD_WALLET_TYPE, "src"));
        WalletResponse target = walletService.createWallet(new CreateWalletRequest(recipient, USD_WALLET_TYPE, "dst"));
        setBalance(source.getUid(), new BigDecimal("100.00"));

        var response = transactionService.confirm(TransactionType.TRANSFER,
                new TransactionRequest(sender, source.getUid(), new BigDecimal("40.00"))
                        .targetWalletUid(target.getUid()));

        assertThat(response.getStatus().name()).isEqualTo("COMPLETED");
        assertThat(walletService.getByUid(source.getUid()).getBalance()).isEqualByComparingTo("59.80");
        assertThat(walletService.getByUid(target.getUid()).getBalance()).isEqualByComparingTo("40.00");
        assertThat(transactionService.getStatus(response.getTransactionId()).getUserUid()).isEqualTo(sender);
    }

    @Test
    void failedCrossShardTransferLeavesBothBalancesUnchanged() throws Exception {
        UUID sender = UUID.randomUUID();
        UUID recipient = userOnOtherShardThan(sender);
        WalletResponse source = walletService.createWallet(new CreateWalletRequest(sender, USD_WALLET_TYPE, "src"));
        WalletResponse target = walletService.createWallet(new CreateWalletRequest(recipient, USD_WALLET_TYPE, "dst"));
        setBalance(source.getUid(), new BigDecimal("10.00"));

        assertThatThrownBy(() -> transactionService.confirm(TransactionType.TRANSFER,
                new TransactionRequest(sender, source.getUid(), new BigDecimal("40.00"))
                        .targetWalletUid(target.getUid())))
                .isInstanceOf(ConflictException.class);

        assertThat(walletService.getByUid(source.getUid()).getBalance()).isEqualByComparingTo("10.00");
        assertThat(walletService.getByUid(target.getUid()).getBalance()).isEqualByComparingTo("0.00");
    }

    private UUID userOnOtherShardThan(UUID user) throws Exception {
        walletService.createWallet(new CreateWalletRequest(user, USD_WALLET_TYPE, "probe"));
        boolean userOnShard0 = !usersOnShard(SHARD_0, List.of(user)).isEmpty();
        while (true) {
            UUID candidate = UUID.randomUUID();
            walletService.createWallet(new CreateWalletRequest(candidate, USD_WALLET_TYPE, "probe"));
            boolean candidateOnShard0 = !usersOnShard(SHARD_0, List.of(candidate)).isEmpty();
            if (candidateOnShard0 != userOnShard0) {
                return candidate;
            }
        }
    }

    private List<UUID> usersOnShard(PostgreSQLContainer shard, List<UUID> users) throws Exception {
        List<UUID> result = new ArrayList<>();
        try (Connection connection = connect(shard);
             PreparedStatement statement = connection.prepareStatement(
                     "select distinct user_uid from transaction.wallets where user_uid = any (?)")) {
            statement.setArray(1, connection.createArrayOf("uuid", users.toArray()));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(resultSet.getObject(1, UUID.class));
                }
            }
        }
        return result;
    }

    private void setBalance(UUID walletUid, BigDecimal balance) throws Exception {
        for (PostgreSQLContainer shard : List.of(SHARD_0, SHARD_1)) {
            try (Connection connection = connect(shard);
                 PreparedStatement statement = connection.prepareStatement(
                         "update transaction.wallets set balance = ? where uid = ?")) {
                statement.setBigDecimal(1, balance);
                statement.setObject(2, walletUid);
                statement.executeUpdate();
            }
        }
    }

    private Connection connect(PostgreSQLContainer shard) throws Exception {
        return DriverManager.getConnection(shard.getJdbcUrl(), shard.getUsername(), shard.getPassword());
    }
}
