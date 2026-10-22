package com.procureflow.app;

import com.procureflow.app.support.TestDatabase;
import java.util.ArrayList;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * P8.2 fail-closed startup evidence: the application verifies the Flyway schema version during
 * context refresh (after Flyway runs, before the context is ready) and refuses to start on a
 * missing, mismatched or unreadable version. Each case runs a real application context against a
 * real PostgreSQL schema; the property {@code app.schema.expected-version} selects the required
 * version, defaulting to 6.
 */
@Testcontainers
class SchemaVersionFailClosedIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();

    JdbcTemplate owner;
    DriverManagerDataSource migratorDataSource;

    @BeforeEach void resetSchema() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("DROP SCHEMA public CASCADE");
        owner.execute("CREATE SCHEMA public");
        owner.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        owner.execute("GRANT USAGE, CREATE ON SCHEMA public TO procureflow_migrator");
        owner.execute("GRANT USAGE ON SCHEMA public TO procureflow_app, procureflow_worker");
        owner.execute("ALTER DEFAULT PRIVILEGES FOR ROLE procureflow_migrator IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO procureflow_app");
        migratorDataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "procureflow_migrator", TestDatabase.migrationPassword());
    }

    @Test
    void migratedSchemaWithExpectedVersion6StartsAndServesVersion6() {
        try (var context = start(Map.of("app.schema.expected-version", "6"))) {
            assertThat(context.isActive()).isTrue();
            assertThat(owner.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                    .isEqualTo("6");
            assertThat(owner.queryForObject("SELECT success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", Boolean.class))
                    .isTrue();
        }
    }

    @Test
    void unsuccessfulLatestHistoryRowFailsClosed() {
        migrate(null);
        owner.update("""
                UPDATE flyway_schema_history SET success = false
                WHERE installed_rank = (SELECT max(installed_rank) FROM flyway_schema_history)
                """);
        assertFailsClosedWith(Map.of("app.schema.expected-version", "6", "spring.flyway.enabled", "false"),
                "not successful");
    }

    @Test
    void repeatableStyleLatestRowWithNullVersionFailsClosed() {
        migrate(null);
        owner.update("""
                UPDATE flyway_schema_history SET version = NULL, success = true
                WHERE installed_rank = (SELECT max(installed_rank) FROM flyway_schema_history)
                """);
        assertFailsClosedWith(Map.of("app.schema.expected-version", "6", "spring.flyway.enabled", "false"),
                "repeatable migration");
    }

    @Test
    void v5SchemaWithExpectedVersion6FailsClosed() {
        migrate("5");
        assertFailsClosed(() -> start(Map.of("app.schema.expected-version", "6", "spring.flyway.target", "5")));
    }

    @Test
    void mismatchedExpectedVersionFailsClosed() {
        migrate(null);
        assertFailsClosed(() -> start(Map.of("app.schema.expected-version", "5", "spring.flyway.enabled", "false")));
    }

    @Test
    void servletApplicationFailsClosedWithMismatchedVersionBeforeServingTraffic() {
        migrate(null);
        assertFailsClosed(() -> start(WebApplicationType.SERVLET,
                Map.of("app.schema.expected-version", "5", "spring.flyway.enabled", "false", "server.port", "0")));
    }

    @Test
    void missingHistoryRowsFailClosed() {
        migrate(null);
        owner.execute("DELETE FROM flyway_schema_history");
        assertFailsClosed(() -> start(Map.of("app.schema.expected-version", "6", "spring.flyway.enabled", "false")));
    }

    @Test
    void emptyDatabaseWithoutMigrationsFailsClosed() {
        // Hibernate structural validation or the schema check fails; either way the context never starts.
        assertThat(catchThrowable(() -> start(Map.of("app.schema.expected-version", "6", "spring.flyway.enabled", "false"))))
                .isNotNull();
    }

    private void migrate(String target) {
        var configuration = Flyway.configure().dataSource(migratorDataSource);
        if (target != null) configuration = configuration.target(target);
        configuration.load().migrate();
    }

    private ConfigurableApplicationContext start(Map<String, String> overrides) {
        return start(WebApplicationType.NONE, overrides);
    }

    private ConfigurableApplicationContext start(WebApplicationType webType, Map<String, String> overrides) {
        var arguments = new ArrayList<String>();
        arguments.add("--spring.datasource.url=" + DATABASE.getJdbcUrl());
        arguments.add("--spring.datasource.username=procureflow_app");
        arguments.add("--spring.datasource.password=" + TestDatabase.appPassword());
        arguments.add("--spring.flyway.user=procureflow_migrator");
        arguments.add("--spring.flyway.password=" + TestDatabase.migrationPassword());
        arguments.add("--spring.main.banner-mode=off");
        arguments.add("--logging.level.root=WARN");
        overrides.forEach((key, value) -> arguments.add("--" + key + "=" + value));
        return new SpringApplicationBuilder(ProcureFlowApplication.class)
                .web(webType)
                .build()
                .run(arguments.toArray(String[]::new));
    }

    private static void assertFailsClosed(Runnable start) {
        var failure = catchThrowable(start::run);
        assertThat(failure).isNotNull();
        assertThat(messageChain(failure)).as(String.join(" | ", messageChain(failure)))
                .anyMatch(message -> message.contains("schema version"));
    }

    private void assertFailsClosedWith(Map<String, String> overrides, String messageFragment) {
        var failure = catchThrowable(() -> start(overrides));
        assertThat(failure).isNotNull();
        var messages = messageChain(failure);
        assertThat(messages).as(String.join(" | ", messages))
                .anyMatch(message -> message.contains("schema version"));
        assertThat(messages).as(String.join(" | ", messages))
                .anyMatch(message -> message.contains(messageFragment));
    }

    private static ArrayList<String> messageChain(Throwable failure) {
        var messages = new ArrayList<String>();
        for (var current = failure; current != null; current = current.getCause()) {
            messages.add(String.valueOf(current.getMessage()));
        }
        return messages;
    }
}
