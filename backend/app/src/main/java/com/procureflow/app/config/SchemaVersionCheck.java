package com.procureflow.app.config;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fail-closed schema-version check.
 *
 * <p>The application runs with Flyway disabled in the long-lived containers; the one-shot
 * {@code migrate} service owns migrations. JPA structural validation can still pass on an
 * index-only or otherwise stale schema, so this check reads the latest
 * {@code flyway_schema_history} row directly and refuses to start unless that row is successful
 * and its version equals {@code app.schema.expected-version}. A latest repeatable migration has a
 * NULL version and can therefore never satisfy this versioned migration requirement.
 *
 * <p>Ordering: {@link SmartInitializingSingleton#afterSingletonsInstantiated()} runs after every
 * singleton — including Flyway's migration initializer when Flyway is enabled — and before the
 * context finishes refreshing and starts serving. That makes the failure happen during startup
 * rather than after an {@code ApplicationReadyEvent}. Contexts without a DataSource (web-slice
 * tests) simply skip the check because no DataSource is available.
 */
@Component
public class SchemaVersionCheck implements SmartInitializingSingleton {
    public static final String EXPECTED_VERSION_PROPERTY = "app.schema.expected-version";
    static final String LATEST_VERSION_SQL =
            "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1";

    private static final Logger log = LoggerFactory.getLogger(SchemaVersionCheck.class);

    private final ObjectProvider<DataSource> dataSources;
    private final Environment environment;
    private volatile String verifiedVersion;

    public SchemaVersionCheck(ObjectProvider<DataSource> dataSources, Environment environment) {
        this.dataSources = dataSources;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var dataSource = dataSources.getIfAvailable();
        if (dataSource == null) {
            log.debug("No DataSource is available; the schema-version check is skipped for this context");
            return;
        }
        var expected = environment.getRequiredProperty(EXPECTED_VERSION_PROPERTY).strip();
        HistoryRow latest;
        try (Connection connection = dataSource.getConnection()) {
            latest = latestHistoryRow(connection);
        } catch (SQLException failure) {
            throw new IllegalStateException("Database schema version check failed: cannot read flyway_schema_history", failure);
        }
        if (latest == null) {
            throw new IllegalStateException("Database schema version is missing from flyway_schema_history; expected '"
                    + expected + "'. Run the one-shot migrate service before starting the application");
        }
        if (!Boolean.TRUE.equals(latest.success())) {
            throw new IllegalStateException("The latest flyway_schema_history row is not successful; refusing to start. "
                    + "Expected schema version '" + expected + "'. Run the one-shot migrate service before starting the application");
        }
        var actual = latest.version() == null ? "" : latest.version().strip();
        if (actual.isBlank()) {
            throw new IllegalStateException("Database schema version is null or blank in the latest flyway_schema_history row "
                    + "(for example a repeatable migration); expected '" + expected
                    + "'. Run the one-shot migrate service before starting the application");
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Database schema version '" + actual + "' does not match the required '"
                    + expected + "'. Run the one-shot migrate service before starting the application");
        }
        verifiedVersion = actual;
        log.info("Database schema version {} matches the expected version", verifiedVersion);
    }

    /** The version observed during startup, or {@code null} when the check was skipped. */
    public String verifiedVersion() {
        return verifiedVersion;
    }

    private static HistoryRow latestHistoryRow(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(LATEST_VERSION_SQL);
             var rows = statement.executeQuery()) {
            if (!rows.next()) return null;
            return new HistoryRow(rows.getString("version"), rows.getObject("success", Boolean.class));
        }
    }

    private record HistoryRow(String version, Boolean success) {}
}
