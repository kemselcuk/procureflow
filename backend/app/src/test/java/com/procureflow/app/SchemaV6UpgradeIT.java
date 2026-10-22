package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.QueryFixtures;
import com.procureflow.app.support.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8.2 upgrade evidence: a populated V5 schema upgrades to the additive V6 index migration with
 * every row unchanged. Rollback disposition is index-only: dropping the two indexes inside a
 * transaction leaves the data readable and is undone by rollback.
 */
@Testcontainers
class SchemaV6UpgradeIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();

    static final UUID UPGRADE_COMPANY = id("upgrade:company");

    static final List<String> DATA_TABLES = List.of("company", "department", "app_identity", "membership",
            "membership_role", "manager_department", "monthly_budget", "purchase_request", "request_history",
            "audit_entry", "outbox_event", "outbox_recipient", "notification_delivery", "in_app_notification");

    @Test
    void populatedV5UpgradesToV6WithoutChangingDataAndIndexesRollBackCleanly() throws Exception {
        var migratorDataSource = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "procureflow_migrator",
                TestDatabase.migrationPassword());
        var owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword()));
        var v5 = Flyway.configure().dataSource(migratorDataSource).target("5").load();
        v5.migrate();
        assertThat(v5.info().current().getVersion().getVersion()).isEqualTo("5");

        seedPopulatedV5Fixture(owner);
        var countsBefore = counts(owner);
        var checksumsBefore = checksums(owner);
        assertThat(countsBefore.get("audit_entry")).isEqualTo(25L);
        assertThat(countsBefore.get("notification_delivery")).isEqualTo(2L);

        var upgrade = Flyway.configure().dataSource(migratorDataSource).load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("6");
        upgrade.validate();

        assertThat(counts(owner)).isEqualTo(countsBefore);
        assertThat(checksums(owner)).isEqualTo(checksumsBefore);
        assertThat(owner.queryForObject("SELECT count(*) FROM flyway_schema_history", Long.class)).isEqualTo(6L);
        assertThat(indexes(owner, "notification_delivery")).contains("ix_notification_delivery_company_outcome");
        assertThat(indexes(owner, "audit_entry")).contains("ix_audit_company_time_id");

        var app = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), "procureflow_app",
                TestDatabase.appPassword()));
        assertThat(app.queryForObject("SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1",
                String.class)).isEqualTo("6");

        try (Connection connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                DATABASE.getPassword())) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("DROP INDEX ix_notification_delivery_company_outcome");
                statement.execute("DROP INDEX ix_audit_company_time_id");
                try (ResultSet rows = statement.executeQuery("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                        + "AND tablename = 'notification_delivery' AND indexname = 'ix_notification_delivery_company_outcome'")) {
                    assertThat(rows.next()).isFalse();
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT count(*) FROM notification_delivery WHERE company_id = '" + UPGRADE_COMPANY
                                + "' AND outcome = 'MATERIALIZED'")) {
                    rows.next();
                    assertThat(rows.getLong(1)).isEqualTo(2L);
                }
                connection.rollback();
            }
        }
        assertThat(indexes(owner, "notification_delivery")).contains("ix_notification_delivery_company_outcome");
        assertThat(indexes(owner, "audit_entry")).contains("ix_audit_company_time_id");
        assertThat(counts(owner)).isEqualTo(countsBefore);
    }

    private static void seedPopulatedV5Fixture(JdbcTemplate owner) {
        var now = Instant.parse("2026-03-01T10:00:00Z");
        var company = id("upgrade:company");
        var department = id("upgrade:department");
        var identity = id("upgrade:identity");
        var membership = id("upgrade:membership");
        var budget = id("upgrade:budget");
        var request = id("upgrade:request");
        var correlation = UUID.randomUUID();
        var event = UUID.randomUUID();
        owner.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, 'upgrade-fixture', 'Upgrade fixture', now())",
                company);
        owner.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')", department, company);
        owner.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', 'upgrade')",
                identity);
        owner.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) VALUES (?, ?, ?, ?, true)",
                membership, company, identity, department);
        owner.update("INSERT INTO membership_role VALUES (?, ?, 'EMPLOYEE')", company, membership);
        owner.update("INSERT INTO monthly_budget (id, company_id, department_id, budget_month, limit_amount, used_amount) VALUES (?, ?, ?, DATE '2026-03-01', 1000, 0)",
                budget, company, department);
        owner.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                    title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'Upgrade request', 'Populated V5 fixture', 100.00, ?, ?, ?)
                """, request, company, identity, membership, department, Timestamp.from(now), Timestamp.from(now), correlation);
        owner.update("""
                INSERT INTO request_history (id, company_id, request_id, request_version, action, to_status,
                    actor_identity_id, actor_membership_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, 0, 'request.created', 'DRAFT', ?, ?, ?, ?)
                """, UUID.randomUUID(), company, request, identity, membership, correlation, Timestamp.from(now));
        owner.update("""
                INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, 'upgrade.fixture', ?, ?, ?)
                """, UUID.randomUUID(), company, identity, membership, request, correlation, Timestamp.from(now));
        owner.update("""
                INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, occurred_at, envelope)
                VALUES (?, ?, ?, 'request.submitted', 1, ?, ?, CAST(? AS jsonb))
                """, event, company, request, correlation, Timestamp.from(now),
                """
                        {"eventId":"%s","companyId":"%s","requestId":"%s","type":"request.submitted","correlationId":"%s","schemaVersion":1}
                        """.formatted(event, company, request, correlation));
        owner.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                company, event, identity, membership);
        var delivery = UUID.randomUUID();
        owner.update("""
                INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, channel, outcome)
                VALUES (?, ?, ?, ?, ?, 'IN_APP', 'MATERIALIZED')
                """, delivery, company, event, identity, membership);
        owner.update("""
                INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, membership_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), delivery, company, event, identity, membership, Timestamp.from(now));
        owner.update("""
                INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, channel, outcome)
                VALUES (?, ?, ?, ?, ?, 'EMAIL', 'MATERIALIZED')
                """, UUID.randomUUID(), company, event, identity, membership);
        for (var index = 0; index < 24; index++) {
            QueryFixtures.seedAuditEntry(owner, company, identity, membership, "upgrade.audit." + index,
                    now.plusSeconds(index));
        }
        assertThat(owner.queryForObject("SELECT limit_amount FROM monthly_budget WHERE id = ?", BigDecimal.class, budget))
                .isEqualByComparingTo("1000");
    }

    private static Map<String, Long> counts(JdbcTemplate jdbc) {
        var counts = new java.util.LinkedHashMap<String, Long>();
        for (var table : DATA_TABLES) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private static Map<String, String> checksums(JdbcTemplate jdbc) {
        var checksums = new java.util.LinkedHashMap<String, String>();
        var orders = Map.of(
                "outbox_recipient", "event_id, identity_id",
                "membership_role", "company_id, membership_id, role",
                "manager_department", "company_id, membership_id, department_id");
        for (var table : DATA_TABLES) {
            var order = orders.getOrDefault(table, "id");
            checksums.put(table, jdbc.queryForObject("SELECT coalesce(md5(string_agg(row_to_json(t)::text, '|' ORDER BY "
                    + order + ")), 'empty') FROM " + table + " t", String.class));
        }
        return checksums;
    }

    private static List<String> indexes(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = ? ORDER BY indexname",
                String.class, table);
    }
}
