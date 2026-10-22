package com.procureflow.app;

import com.procureflow.app.identity.DemoData;
import com.procureflow.app.support.ExplainPlans;
import com.procureflow.app.support.QueryFixtures;
import com.procureflow.app.support.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8.2 plan evidence on a deterministic populated fixture. Every candidate index is created only
 * inside a transaction that is rolled back afterwards, so the same test first records the
 * pre-index plan and then the plan the candidate index would produce. The printed summaries are
 * the EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) evidence; indexes are added to V6 only when this
 * measurement shows a concrete benefit.
 */
@Testcontainers
class QueryPlanEvidenceIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();

    static final int MEMBERS = 100;
    static final int TARGET_EVENTS = 50;
    static final int FOREIGN_EVENTS = 450;
    static final int TARGET_AUDIT_ROWS = 5_000;
    static final int FOREIGN_AUDIT_ROWS = 45_000;

    static JdbcTemplate owner;

    @BeforeAll static void migrateAndPopulate() {
        var migrator = new DriverManagerDataSource(DATABASE.getJdbcUrl(), "procureflow_migrator", TestDatabase.migrationPassword());
        Flyway.configure().dataSource(migrator).load().migrate();
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("""
                TRUNCATE notification_diagnostic, email_job, in_app_notification, notification_delivery, notification_inbox,
                    outbox_dispatch, outbox_recipient, outbox_event, request_history, api_command, budget_charge,
                    approval_step, audit_entry, manager_department, membership_role, monthly_budget, membership,
                    department, app_identity, company CASCADE
                """);
        for (var company : new String[] {"a", "b"}) {
            owner.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, now())", id(company),
                    "plan-company-" + company, "Plan company " + company);
            owner.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')",
                    id(company + ":engineering"), id(company));
        }
        QueryFixtures.populateDeliveryBacklog(owner, id("a"), id("a:engineering"), MEMBERS, TARGET_EVENTS, 20);
        QueryFixtures.populateDeliveryBacklog(owner, id("b"), id("b:engineering"), MEMBERS, FOREIGN_EVENTS, 20);
        seedAudit(id("a"), TARGET_AUDIT_ROWS, 0);
        seedAudit(id("b"), FOREIGN_AUDIT_ROWS, 1_000_000);
        owner.execute("ANALYZE audit_entry");
        owner.execute("ANALYZE notification_delivery");
    }

    @Test
    void populatedFixtureHasTheExpectedSelectivity() {
        assertThat(owner.queryForObject("SELECT count(*) FROM notification_delivery", Long.class)).isEqualTo(50_000L);
        assertThat(owner.queryForObject("SELECT count(*) FROM notification_delivery WHERE company_id = ? AND outcome = 'MATERIALIZED'",
                Long.class, id("a"))).isEqualTo((long) MEMBERS * TARGET_EVENTS / 20 * 19);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry", Long.class)).isEqualTo(50_000L);
        assertThat(owner.queryForObject("SELECT count(*) FROM audit_entry WHERE company_id = ?", Long.class, id("a")))
                .isEqualTo((long) TARGET_AUDIT_ROWS);
    }

    @Test
    void notificationDeliveryOutcomeCountPlan() throws Exception {
        var sql = "SELECT count(*) FROM notification_delivery WHERE company_id = '" + id("a")
                + "' AND outcome = 'MATERIALIZED'";
        var measured = measureBoth(sql, "ix_notification_delivery_company_outcome",
                "CREATE INDEX ix_notification_delivery_company_outcome ON notification_delivery(company_id, outcome)");
        var baseline = measured.baseline();
        var indexed = measured.indexed();
        System.out.println("[PLAN] notification_delivery count baseline: " + baseline.summary());
        System.out.println("[PLAN] notification_delivery count with (company_id, outcome): " + indexed.summary());
        assertThat(owner.queryForObject(sql, Long.class)).isEqualTo((long) MEMBERS * TARGET_EVENTS / 20 * 19);
        assertThat(indexed.indexes()).contains("ix_notification_delivery_company_outcome");
        assertThat(indexed.sharedHitBlocks() + indexed.sharedReadBlocks())
                .isLessThan(baseline.sharedHitBlocks() + baseline.sharedReadBlocks());
        assertThat(indexed.totalCost()).isLessThan(baseline.totalCost());
    }

    @Test
    void auditPageOrderingPlan() throws Exception {
        var sql = "SELECT id, action, entity_id, actor_identity_id, actor_membership_id, correlation_id, occurred_at "
                + "FROM audit_entry WHERE company_id = '" + id("a") + "' ORDER BY occurred_at DESC, id DESC LIMIT 20 OFFSET 0";
        var measured = measureBoth(sql, "ix_audit_company_time_id",
                "CREATE INDEX ix_audit_company_time_id ON audit_entry(company_id, occurred_at DESC, id DESC)");
        var baseline = measured.baseline();
        var indexed = measured.indexed();
        System.out.println("[PLAN] audit page baseline: " + baseline.summary());
        System.out.println("[PLAN] audit page with (company_id, occurred_at DESC, id DESC): " + indexed.summary());
        assertThat(owner.queryForObject("SELECT count(*) FROM (" + sql + ") page", Long.class)).isEqualTo(20);
        assertThat(baseline.nodeTypes()).contains("Incremental Sort");
        assertThat(indexed.nodeTypes()).doesNotContain("Incremental Sort");
        assertThat(indexed.indexes()).contains("ix_audit_company_time_id");
        assertThat(indexed.sharedHitBlocks() + indexed.sharedReadBlocks())
                .isLessThan(baseline.sharedHitBlocks() + baseline.sharedReadBlocks());
    }

    private static void seedAudit(UUID company, int rows, int timeOffsetSeconds) {
        var identity = DemoData.id(QueryFixtures.memberKey(company, 0) + ":identity");
        var membership = DemoData.id(QueryFixtures.memberKey(company, 0) + ":membership");
        var batch = new java.util.ArrayList<Object[]>();
        var base = java.time.Instant.parse("2026-02-01T00:00:00Z").plusSeconds(timeOffsetSeconds);
        for (var index = 0; index < rows; index++) {
            batch.add(new Object[] {java.util.UUID.randomUUID(), company, identity, membership, "fixture.action",
                    java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.sql.Timestamp.from(base.plusSeconds(index))});
        }
        owner.batchUpdate("""
                INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, batch);
    }

    private record Measured(ExplainPlans.Plan baseline, ExplainPlans.Plan indexed) {}

    /**
     * Measures the same query with the candidate index absent and present. The index is dropped
     * and re-created inside one transaction that is rolled back, so the migrated V6 schema is
     * unchanged afterwards.
     */
    private static Measured measureBoth(String sql, String indexName, String createIndex) throws Exception {
        try (Connection connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword())) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP INDEX IF EXISTS " + indexName);
                var baseline = explain(statement, sql);
                statement.execute(createIndex);
                var indexed = explain(statement, sql);
                return new Measured(baseline, indexed);
            } finally {
                connection.rollback();
            }
        }
    }

    private static ExplainPlans.Plan explain(Statement statement, String sql) throws Exception {
        try (ResultSet rows = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
            rows.next();
            return ExplainPlans.parse(rows.getString(1));
        }
    }
}
