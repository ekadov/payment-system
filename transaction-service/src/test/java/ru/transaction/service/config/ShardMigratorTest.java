package ru.transaction.service.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardMigratorTest {

    private static final String SHARDING_URL = "jdbc:shardingsphere:classpath:sharding.yaml?placeholder-type=system_props";

    // Интеграционные тесты в той же JVM задают URL шардов через системные свойства.
    @BeforeEach
    @AfterEach
    void clearProperties() {
        System.clearProperty("transaction.shard0.url");
        System.clearProperty("transaction.shard1.url");
    }

    @Test
    void readsShardsFromShardingConfigurationWithPlaceholders() {
        System.setProperty("transaction.shard0.url", "jdbc:postgresql://override:5432/db");

        Map<String, Map<String, Object>> shards = new ShardMigrator(SHARDING_URL).shards();

        assertThat(shards).containsOnlyKeys("shard0", "shard1");
        assertThat(shards.get("shard0")).containsEntry("jdbcUrl", "jdbc:postgresql://override:5432/db")
                .containsEntry("username", "postgres");
        assertThat(shards.get("shard1").get("jdbcUrl"))
                .isEqualTo("jdbc:postgresql://transaction-postgres-1:5432/transaction_service?currentSchema=transaction&stringtype=unspecified");
    }

    @Test
    void rejectsNonShardingUrl() {
        assertThatThrownBy(() -> new ShardMigrator("jdbc:postgresql://localhost/db"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
