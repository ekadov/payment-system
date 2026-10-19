package ru.transaction.service.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.yaml.YamlJDBCConfiguration;
import org.apache.shardingsphere.infra.url.core.ShardingSphereURL;
import org.apache.shardingsphere.infra.url.core.ShardingSphereURLLoadEngine;
import org.apache.shardingsphere.infra.util.yaml.YamlEngine;
import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Применяет миграции Flyway к каждому физическому шарду.
 */
@Slf4j
public class ShardMigrator {

    static final String SCHEMA = "transaction";
    private static final String DRIVER_URL_PREFIX = "jdbc:shardingsphere:";
    private static final String MIGRATIONS = "classpath:migration";

    private final String shardingUrl;

    public ShardMigrator(String shardingUrl) {
        if (shardingUrl == null || !shardingUrl.startsWith(DRIVER_URL_PREFIX)) {
            throw new IllegalArgumentException("Not a ShardingSphere JDBC URL: " + shardingUrl);
        }
        this.shardingUrl = shardingUrl;
    }

    public void migrate() {
        shards().forEach((name, shard) -> migrate(name, shard));
    }

    Map<String, Map<String, Object>> shards() {
        ShardingSphereURL url = ShardingSphereURL.parse(shardingUrl.substring(DRIVER_URL_PREFIX.length()));
        byte[] content = new ShardingSphereURLLoadEngine(url).loadContent();
        try {
            return YamlEngine.unmarshal(content, YamlJDBCConfiguration.class).getDataSources();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read ShardingSphere configuration " + shardingUrl, exception);
        }
    }

    private void migrate(String name, Map<String, Object> shard) {
        String jdbcUrl = String.valueOf(shard.get("jdbcUrl"));
        String username = String.valueOf(shard.get("username"));
        String password = String.valueOf(shard.get("password"));
        String baselineVersion = legacyBaselineVersion(jdbcUrl, username, password);
        Flyway flyway = Flyway.configure()
                .dataSource(jdbcUrl, username, password)
                .schemas(SCHEMA)
                .locations(MIGRATIONS)
                .baselineOnMigrate(baselineVersion != null)
                .baselineVersion(baselineVersion == null ? "1" : baselineVersion)
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("Shard migrated: shard={}, baseline={}, applied={}, version={}",
                name, baselineVersion, applied, flyway.info().current().getVersion());
    }

    private String legacyBaselineVersion(String jdbcUrl, String username, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            if (tableExists(connection, "flyway_schema_history") || !tableExists(connection, "wallets")) {
                return null;
            }
            return tableExists(connection, "outbox_events") ? "2" : "1";
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot inspect shard schema: " + jdbcUrl, exception);
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, SCHEMA);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }
}
