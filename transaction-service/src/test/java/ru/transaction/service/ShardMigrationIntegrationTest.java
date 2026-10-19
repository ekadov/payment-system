package ru.transaction.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.transaction.service.config.ShardMigrator;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.transaction.service.testsupport.TestData.USD_WALLET_TYPE;

/**
 * Обновление шардов, схема которых была создана init-скриптами PostgreSQL до перехода на Flyway.
 */
@Testcontainers
class ShardMigrationIntegrationTest {

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=system_props";
    private static final BigDecimal BALANCE = new BigDecimal("123.45");

    /** Окружение, запущенное до появления outbox: только V1. */
    @Container
    static final PostgreSQLContainer V1_SHARD = shard("migration/V1__init_transactions_schema.sql");
    /** Окружение, где V1 и V2 уже выполнены init-скриптами. */
    @Container
    static final PostgreSQLContainer V2_SHARD = shard("migration/V1__init_transactions_schema.sql",
            "migration/V2__outbox_events.sql");

    private static final UUID V1_WALLET = UUID.randomUUID();
    private static final UUID V2_WALLET = UUID.randomUUID();

    @BeforeAll
    static void legacyData() throws Exception {
        System.setProperty("transaction.shard0.url", V1_SHARD.getJdbcUrl() + "&currentSchema=transaction");
        System.setProperty("transaction.shard1.url", V2_SHARD.getJdbcUrl() + "&currentSchema=transaction");
        insertWallet(V1_SHARD, V1_WALLET);
        insertWallet(V2_SHARD, V2_WALLET);
    }

    @AfterAll
    static void clearProperties() {
        System.clearProperty("transaction.shard0.url");
        System.clearProperty("transaction.shard1.url");
    }

    @Test
    void upgradesLegacyShardsWithoutLosingData() throws Exception {
        new ShardMigrator(SHARDING_URL).migrate();

        assertThat(tableExists(V1_SHARD, "outbox_events")).isTrue();
        assertThat(history(V1_SHARD)).containsExactly("1:BASELINE", "2:SQL", "3:SQL");
        assertThat(balance(V1_SHARD, V1_WALLET)).isEqualByComparingTo(BALANCE);

        assertThat(history(V2_SHARD)).containsExactly("2:BASELINE", "3:SQL");
        assertThat(balance(V2_SHARD, V2_WALLET)).isEqualByComparingTo(BALANCE);

        // Повторный запуск (рестарт приложения) ничего не меняет.
        new ShardMigrator(SHARDING_URL).migrate();
        assertThat(history(V1_SHARD)).containsExactly("1:BASELINE", "2:SQL", "3:SQL");
        assertThat(history(V2_SHARD)).containsExactly("2:BASELINE", "3:SQL");
    }

    private static PostgreSQLContainer shard(String... initScripts) {
        return new PostgreSQLContainer("postgres:17")
                .withDatabaseName("transaction_service")
                .withUsername("postgres")
                .withPassword("postgres")
                .withInitScripts(initScripts);
    }

    private static void insertWallet(PostgreSQLContainer shard, UUID walletUid) throws Exception {
        try (Connection connection = connect(shard);
             PreparedStatement statement = connection.prepareStatement("""
                     insert into transaction.wallets (uid, name, wallet_type_uid, user_uid, status, balance)
                     values (?, 'legacy', ?, ?, 'ACTIVE', ?)""")) {
            statement.setObject(1, walletUid);
            statement.setObject(2, USD_WALLET_TYPE);
            statement.setObject(3, UUID.randomUUID());
            statement.setBigDecimal(4, BALANCE);
            statement.executeUpdate();
        }
    }

    private static BigDecimal balance(PostgreSQLContainer shard, UUID walletUid) throws Exception {
        try (Connection connection = connect(shard);
             PreparedStatement statement = connection.prepareStatement(
                     "select balance from transaction.wallets where uid = ?")) {
            statement.setObject(1, walletUid);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getBigDecimal(1);
            }
        }
    }

    private static List<String> history(PostgreSQLContainer shard) throws Exception {
        try (Connection connection = connect(shard);
             PreparedStatement statement = connection.prepareStatement("""
                     select version, type from transaction.flyway_schema_history
                     where version is not null and success order by installed_rank""");
             ResultSet resultSet = statement.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(resultSet.getString(1) + ":" + resultSet.getString(2));
            }
            return rows;
        }
    }

    private static boolean tableExists(PostgreSQLContainer shard, String table) throws Exception {
        try (Connection connection = connect(shard);
             PreparedStatement statement = connection.prepareStatement(
                     "select 1 from information_schema.tables where table_schema = 'transaction' and table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static Connection connect(PostgreSQLContainer shard) throws Exception {
        return DriverManager.getConnection(shard.getJdbcUrl(), shard.getUsername(), shard.getPassword());
    }
}
