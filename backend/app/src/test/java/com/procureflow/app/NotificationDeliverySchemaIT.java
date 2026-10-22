package com.procureflow.app;

import com.procureflow.app.support.TestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@Testcontainers
class NotificationDeliverySchemaIT {
    private static final String APP_USER = "procureflow_app";
    private static final String WORKER_USER = "procureflow_worker";
    private static final String MIGRATOR_USER = "procureflow_migrator";

    @Container
    static final PostgreSQLContainer DATABASE = TestDatabase.create();

    static String url;
    static JdbcTemplate migrator;
    static JdbcTemplate owner;
    static Fixture old;

    record Fixture(UUID company, UUID department, UUID requesterIdentity, UUID requesterMembership,
                   UUID otherIdentity, UUID otherMembership, UUID request, UUID event, UUID budget) {}

    @BeforeAll
    static void migrateThroughV4BackfillFixtureThenV5() {
        url = DATABASE.getJdbcUrl();
        var migration = new DriverManagerDataSource(url, MIGRATOR_USER, TestDatabase.migrationPassword());
        var before = Flyway.configure().dataSource(migration).target("4").load();
        before.migrate();
        assertThat(before.info().current().getVersion().getVersion()).isEqualTo("4");
        migrator = new JdbcTemplate(migration);
        // Independent observation connection on the database owner credentials. Used only to read
        // pg_stat_activity/pg_blocking_pids; every permission assertion stays on the app/worker logins.
        owner = new JdbcTemplate(new DriverManagerDataSource(url, DATABASE.getUsername(), DATABASE.getPassword()));

        // Owned fixture only, committed before V5, so dispatch backfill is exercised on a real old event.
        old = fixture();

        var upgrade = Flyway.configure().dataSource(migration).target("5").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("5");
        upgrade.validate();
    }

    @Test
    void backfillsOldEventsAsPendingWithoutChangingBudgets() throws Exception {
        var dispatch = migrator.queryForMap("SELECT * FROM outbox_dispatch WHERE event_id = ?", old.event);
        assertThat(dispatch.get("company_id")).isEqualTo(old.company);
        assertThat(dispatch.get("state")).isEqualTo("PENDING");
        assertThat(dispatch.get("attempts")).isEqualTo(0);
        assertThat(dispatch.get("next_attempt_at")).isNotNull();
        assertThat(dispatch.get("lease_token")).isNull();
        assertThat(dispatch.get("lease_until")).isNull();
        assertThat(dispatch.get("published_at")).isNull();
        assertThat(dispatch.get("error_code")).isNull();
        assertThat(migrator.queryForObject("SELECT count(*) FROM outbox_dispatch WHERE event_id = ?", Integer.class,
                old.event)).isEqualTo(1);

        var budget = migrator.queryForMap("SELECT limit_amount, used_amount, version FROM monthly_budget WHERE id = ?",
                old.budget);
        assertThat((BigDecimal) budget.get("limit_amount")).isEqualByComparingTo("1000.00");
        assertThat((BigDecimal) budget.get("used_amount")).isZero();
        assertThat(budget.get("version")).isEqualTo(0L);
        assertThat(migrator.queryForObject("SELECT count(*) FROM budget_charge WHERE request_id = ?", Integer.class,
                old.request)).isZero();
    }

    @Test
    void triggerInitializesDispatchInTheSameApplicationTransaction() throws Exception {
        var committed = fixture();
        var event = UUID.randomUUID();
        try (var app = appConnection()) {
            app.setAutoCommit(false);
            try {
                insertEvent(app, committed.company, committed.request, event, Instant.now());
                insertRecipient(app, committed.company, event, committed.requesterIdentity, committed.requesterMembership);
                assertThat(scalar(app, Integer.class, "SELECT count(*) FROM outbox_dispatch WHERE event_id = ?", event))
                        .isEqualTo(1);
                app.rollback();
            } finally {
                app.setAutoCommit(true);
            }
        }
        assertThat(migrator.queryForObject("SELECT count(*) FROM outbox_event WHERE id = ?", Integer.class, event)).isZero();
        assertThat(migrator.queryForObject("SELECT count(*) FROM outbox_recipient WHERE event_id = ?", Integer.class, event))
                .isZero();
        assertThat(migrator.queryForObject("SELECT count(*) FROM outbox_dispatch WHERE event_id = ?", Integer.class, event))
                .isZero();

        try (var app = appConnection()) {
            insertEvent(app, committed.company, committed.request, event, Instant.now());
            insertRecipient(app, committed.company, event, committed.requesterIdentity, committed.requesterMembership);
        }
        var dispatch = migrator.queryForMap("SELECT * FROM outbox_dispatch WHERE event_id = ?", event);
        assertThat(dispatch.get("state")).isEqualTo("PENDING");
        assertThat(dispatch.get("attempts")).isEqualTo(0);
        assertThat(dispatch.get("next_attempt_at")).isNotNull();
        assertThat(dispatch.get("lease_token")).isNull();
    }

    @Test
    void runtimeRolesHaveOnlyTheirIntendedCapabilities() throws Exception {
        var f = fixture();
        try (var worker = workerConnection(); var app = appConnection()) {
            assertThat(scalar(worker, String.class, "SELECT current_user")).isEqualTo(WORKER_USER);
            assertThat(scalar(app, String.class, "SELECT current_user")).isEqualTo(APP_USER);

            assertThat(scalar(worker, UUID.class, "SELECT id FROM company WHERE id = ?", f.company)).isEqualTo(f.company);
            assertThat(scalar(worker, UUID.class, "SELECT id FROM membership WHERE id = ?", f.requesterMembership))
                    .isEqualTo(f.requesterMembership);
            assertThat(scalar(worker, Boolean.class, "SELECT active FROM membership WHERE id = ?", f.requesterMembership))
                    .isTrue();
            assertThat(scalar(worker, UUID.class, "SELECT id FROM outbox_event WHERE id = ?", f.event)).isEqualTo(f.event);
            assertThat(scalar(worker, UUID.class, "SELECT membership_id FROM outbox_recipient WHERE event_id = ?",
                    f.event)).isEqualTo(f.requesterMembership);

            denied(() -> scalar(worker, String.class, "SELECT name FROM company WHERE id = ?", f.company));
            denied(() -> scalar(worker, UUID.class, "SELECT department_id FROM membership WHERE id = ?",
                    f.requesterMembership));
            for (var table : List.of("purchase_request", "app_identity", "request_history", "audit_entry",
                    "membership_role", "manager_department", "monthly_budget", "approval_step", "budget_charge", "api_command")) {
                denied(() -> scalar(worker, Integer.class, "SELECT count(*) FROM " + table));
            }
            denied(() -> worker.createStatement().execute("CREATE TABLE public.worker_forbidden (id int)"));

            denied(() -> update(worker, "UPDATE company SET name = name WHERE id = ?", f.company));
            denied(() -> update(worker, "UPDATE membership SET active = active WHERE id = ?", f.requesterMembership));
            denied(() -> update(worker, "UPDATE monthly_budget SET used_amount = used_amount WHERE id = ?", f.budget));
            denied(() -> update(worker, "UPDATE purchase_request SET title = 'forged' WHERE id = ?", f.request));
            denied(() -> update(worker, "UPDATE request_history SET action = 'forged' WHERE company_id = ?", f.company));
            denied(() -> update(worker, "DELETE FROM request_history WHERE company_id = ?", f.company));
            denied(() -> update(worker, "UPDATE audit_entry SET action = 'forged' WHERE company_id = ?", f.company));
            denied(() -> update(worker, "DELETE FROM audit_entry WHERE company_id = ?", f.company));
            denied(() -> update(worker, "DELETE FROM outbox_event WHERE id = ?", f.event));
            denied(() -> update(worker, "UPDATE outbox_event SET event_type = event_type WHERE id = ?", f.event));
            denied(() -> update(worker, "UPDATE outbox_recipient SET email = email WHERE event_id = ?", f.event));
            denied(() -> update(worker, "DELETE FROM outbox_recipient WHERE event_id = ?", f.event));
            denied(() -> update(app, "UPDATE outbox_event SET event_type = event_type WHERE id = ?", f.event));
            denied(() -> update(app, "DELETE FROM outbox_event WHERE id = ?", f.event));
            denied(() -> update(app, "UPDATE outbox_recipient SET email = email WHERE event_id = ?", f.event));
            denied(() -> update(app, "DELETE FROM outbox_recipient WHERE event_id = ?", f.event));
            denied(() -> update(worker, "INSERT INTO audit_entry (id, company_id, actor_identity_id, actor_membership_id, action, entity_id) "
                    + "VALUES (?, ?, ?, ?, 'forged', ?)", UUID.randomUUID(), f.company, f.requesterIdentity,
                    f.requesterMembership, UUID.randomUUID()));

            var foreignEvent = UUID.randomUUID();
            denied(() -> insertEvent(worker, f.company, f.request, foreignEvent, Instant.now()));
            denied(() -> insertRecipient(worker, f.company, foreignEvent, f.requesterIdentity, f.requesterMembership));

            for (var table : List.of("outbox_dispatch", "notification_inbox", "notification_delivery",
                    "in_app_notification", "email_job")) {
                assertThat(scalar(app, Integer.class, "SELECT count(*) FROM " + table)).isNotNull();
            }
            denied(() -> scalar(app, Integer.class, "SELECT count(*) FROM notification_diagnostic"));
            denied(() -> update(worker, "UPDATE notification_inbox SET processed_at = now() WHERE company_id = ?", f.company));
            denied(() -> update(worker, "INSERT INTO outbox_dispatch (event_id, company_id) VALUES (?, ?)",
                    UUID.randomUUID(), f.company));
            denied(() -> update(app, "UPDATE notification_inbox SET processed_at = now() WHERE company_id = ?", f.company));
            denied(() -> update(app, "UPDATE outbox_dispatch SET state = 'PENDING' WHERE event_id = ?", f.event));
            denied(() -> update(app, "INSERT INTO outbox_dispatch (event_id, company_id) VALUES (?, ?)",
                    UUID.randomUUID(), f.company));
            denied(() -> update(app, "INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)",
                    f.company, UUID.randomUUID()));
            denied(() -> insertDelivery(app, UUID.randomUUID(), f.company, f.event, f.requesterIdentity,
                    f.requesterMembership, "IN_APP", "MATERIALIZED", null));
            denied(() -> update(app, "INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, "
                    + "membership_id) VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), UUID.randomUUID(), f.company,
                    f.event, f.requesterIdentity, f.requesterMembership));
            denied(() -> update(app, "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, "
                    + "membership_id) VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), UUID.randomUUID(), f.company,
                    f.event, f.requesterIdentity, f.requesterMembership));
            denied(() -> update(app, "UPDATE notification_delivery SET outcome = outcome WHERE company_id = ?", f.company));
            denied(() -> update(app, "DELETE FROM in_app_notification WHERE company_id = ?", f.company));

            denied(() -> execute(app, "SELECT public.lock_notification_company(?)", f.company));
            denied(() -> scalar(worker, UUID.class, "SELECT id FROM company WHERE id = ? FOR UPDATE", f.company));

            var keycloakUrl = "jdbc:postgresql://" + DATABASE.getHost() + ":" + DATABASE.getMappedPort(5432)
                    + "/keycloak";
            var keycloak = failure(() -> {
                try (var ignored = DriverManager.getConnection(keycloakUrl, WORKER_USER, TestDatabase.workerPassword())) {
                    // A successful connection here would violate the database boundary.
                }
            });
            assertState(keycloak, "42501");

            worker.setAutoCommit(false);
            try {
                execute(worker, "SELECT public.lock_notification_company(?)", f.company);
            } finally {
                worker.rollback();
                worker.setAutoCommit(true);
            }
        }
    }

    @Test
    void deliveryBindingsAndSkippedOutcomesCannotBeForged() throws Exception {
        var f = fixture();
        var g = fixture();
        var inApp = UUID.randomUUID();
        var email = UUID.randomUUID();
        try (var worker = workerConnection(); var app = appConnection()) {
            insertDelivery(worker, inApp, f.company, f.event, f.requesterIdentity, f.requesterMembership,
                    "IN_APP", "MATERIALIZED", null);
            insertDelivery(worker, email, f.company, f.event, f.requesterIdentity, f.requesterMembership,
                    "EMAIL", "MATERIALIZED", null);
            assertThat(scalar(worker, String.class, "SELECT channel FROM notification_delivery WHERE id = ?", inApp))
                    .isEqualTo("IN_APP");
            assertThat(scalar(worker, String.class, "SELECT outcome FROM notification_delivery WHERE id = ?", email))
                    .isEqualTo("MATERIALIZED");
            assertThat(scalar(worker, Timestamp.class, "SELECT outcome_at FROM notification_delivery WHERE id = ?",
                    inApp)).isNotNull();

            var duplicate = failure(() -> insertDelivery(worker, UUID.randomUUID(), f.company, f.event,
                    f.requesterIdentity, f.requesterMembership, "IN_APP", "MATERIALIZED", null));
            assertState(duplicate, "23505");
            assertConstraint(duplicate, "uq_notification_delivery_event_identity_channel");

            var wrongMembershipFixture = fixture();
            var wrongMembership = failure(() -> insertDelivery(worker, UUID.randomUUID(), wrongMembershipFixture.company,
                    wrongMembershipFixture.event, wrongMembershipFixture.requesterIdentity,
                    wrongMembershipFixture.otherMembership, "IN_APP", "MATERIALIZED", null));
            assertState(wrongMembership, "23503");
            assertConstraint(wrongMembership, "fk_notification_delivery_recipient");

            var wrongTenantFixture = fixture();
            var wrongTenant = failure(() -> insertDelivery(worker, UUID.randomUUID(), wrongTenantFixture.company,
                    f.event, f.requesterIdentity, f.requesterMembership, "IN_APP", "MATERIALIZED", null));
            assertState(wrongTenant, "23503");
            assertConstraint(wrongTenant, "fk_notification_delivery_recipient");

            var materializedWithReason = failure(() -> insertDelivery(worker, UUID.randomUUID(), g.company, g.event,
                    g.requesterIdentity, g.requesterMembership, "IN_APP", "MATERIALIZED", "not_needed"));
            assertState(materializedWithReason, "23514");
            assertConstraint(materializedWithReason, "ck_notification_delivery_reason");

            var skippedWithoutReason = failure(() -> insertDelivery(worker, UUID.randomUUID(), g.company, g.event,
                    g.requesterIdentity, g.requesterMembership, "IN_APP", "SKIPPED", null));
            assertState(skippedWithoutReason, "23514");
            assertConstraint(skippedWithoutReason, "ck_notification_delivery_reason");

            var invalidChannel = failure(() -> insertDelivery(worker, UUID.randomUUID(), g.company, g.event,
                    g.requesterIdentity, g.requesterMembership, "SMS", "MATERIALIZED", null));
            assertState(invalidChannel, "23514");
            assertConstraint(invalidChannel, "ck_notification_delivery_channel");

            var skipped = UUID.randomUUID();
            insertDelivery(worker, skipped, g.company, g.event, g.requesterIdentity, g.requesterMembership,
                    "IN_APP", "SKIPPED", "recipient_inactive");
            denied(() -> update(worker, "UPDATE notification_delivery SET outcome = 'MATERIALIZED' WHERE id = ?", skipped));
            denied(() -> update(app, "UPDATE notification_delivery SET outcome = 'MATERIALIZED' WHERE id = ?", skipped));
            var replay = failure(() -> insertDelivery(worker, UUID.randomUUID(), g.company, g.event,
                    g.requesterIdentity, g.requesterMembership, "IN_APP", "MATERIALIZED", null));
            assertState(replay, "23505");
            assertConstraint(replay, "uq_notification_delivery_event_identity_channel");

            denied(() -> update(worker, "DELETE FROM notification_delivery WHERE id = ?", inApp));
            denied(() -> update(app, "DELETE FROM notification_delivery WHERE id = ?", inApp));
            assertThat(migrator.queryForObject("SELECT used_amount FROM monthly_budget WHERE id = ?", BigDecimal.class,
                    f.budget)).isZero();
            assertThat(migrator.queryForObject("SELECT count(*) FROM budget_charge WHERE request_id = ?", Integer.class,
                    f.request)).isZero();
        }
    }

    @Test
    void inboxIsAppendOnlyAndExactlyOncePerCompanyEvent() throws Exception {
        var f = fixture();
        try (var worker = workerConnection(); var app = appConnection()) {
            update(worker, "INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)", f.company, f.event);
            assertThat(scalar(worker, Timestamp.class, "SELECT processed_at FROM notification_inbox WHERE company_id = ? AND event_id = ?",
                    f.company, f.event)).isNotNull();
            var duplicate = failure(() -> update(worker,
                    "INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)", f.company, f.event));
            assertState(duplicate, "23505");
            assertConstraint(duplicate, "notification_inbox_pkey");
            denied(() -> update(worker, "UPDATE notification_inbox SET processed_at = now() WHERE event_id = ?", f.event));
            denied(() -> update(worker, "DELETE FROM notification_inbox WHERE event_id = ?", f.event));
            denied(() -> update(app, "UPDATE notification_inbox SET processed_at = now() WHERE event_id = ?", f.event));
            denied(() -> update(app, "DELETE FROM notification_inbox WHERE event_id = ?", f.event));
            denied(() -> update(app, "INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)",
                    f.company, UUID.randomUUID()));

            var unknownEvent = failure(() -> update(worker,
                    "INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)", f.company, UUID.randomUUID()));
            assertState(unknownEvent, "23503");
            assertConstraint(unknownEvent, "fk_notification_inbox_event");
        }
    }

    @Test
    void inAppNotificationsAndEmailJobsKeepMaterializedBindings() throws Exception {
        var f = fixture();
        var skippedFixture = fixture();
        var inApp = UUID.randomUUID();
        var email = UUID.randomUUID();
        var job = UUID.randomUUID();
        var notification = UUID.randomUUID();
        try (var worker = workerConnection(); var app = appConnection()) {
            insertDelivery(worker, inApp, f.company, f.event, f.requesterIdentity, f.requesterMembership,
                    "IN_APP", "MATERIALIZED", null);
            insertDelivery(worker, email, f.company, f.event, f.requesterIdentity, f.requesterMembership,
                    "EMAIL", "MATERIALIZED", null);

            insertInApp(worker, notification, inApp, f.company, f.event, f.requesterIdentity, f.requesterMembership);
            assertThat(scalar(worker, String.class, "SELECT channel FROM in_app_notification WHERE id = ?", notification))
                    .isEqualTo("IN_APP");
            assertThat(scalar(worker, String.class, "SELECT delivery_outcome FROM in_app_notification WHERE id = ?",
                    notification)).isEqualTo("MATERIALIZED");
            assertThat(scalar(worker, Timestamp.class, "SELECT read_at FROM in_app_notification WHERE id = ?",
                    notification)).isNull();
            assertThat(scalar(worker, Timestamp.class, "SELECT created_at FROM in_app_notification WHERE id = ?",
                    notification)).isNotNull();
            assertThat(scalar(app, UUID.class, "SELECT id FROM in_app_notification WHERE id = ?", notification))
                    .isEqualTo(notification);
            update(app, "UPDATE in_app_notification SET read_at = now() WHERE id = ?", notification);
            assertThat(scalar(app, Timestamp.class, "SELECT read_at FROM in_app_notification WHERE id = ?",
                    notification)).isNotNull();
            denied(() -> update(app, "UPDATE in_app_notification SET identity_id = ? WHERE id = ?",
                    f.otherIdentity, notification));
            denied(() -> update(worker, "UPDATE in_app_notification SET read_at = now() WHERE id = ?", notification));

            var duplicateNotification = failure(() -> insertInApp(worker, UUID.randomUUID(), inApp, f.company, f.event,
                    f.requesterIdentity, f.requesterMembership));
            assertState(duplicateNotification, "23505");
            assertConstraint(duplicateNotification, "uq_in_app_notification_delivery");

            var notificationForEmail = failure(() -> insertInApp(worker, UUID.randomUUID(), email, f.company, f.event,
                    f.requesterIdentity, f.requesterMembership));
            assertState(notificationForEmail, "23503");
            assertConstraint(notificationForEmail, "fk_in_app_notification_delivery");

            update(worker, "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", job, email, f.company, f.event, f.requesterIdentity, f.requesterMembership);
            assertThat(scalar(worker, String.class, "SELECT state FROM email_job WHERE id = ?", job)).isEqualTo("PENDING");
            assertThat(scalar(worker, Integer.class, "SELECT attempts FROM email_job WHERE id = ?", job)).isZero();
            assertThat(scalar(worker, String.class, "SELECT recipient_address FROM email_job WHERE id = ?", job))
                    .isEqualTo(f.requesterIdentity + "@demo.invalid");
            assertThat(scalar(worker, Timestamp.class, "SELECT next_attempt_at FROM email_job WHERE id = ?", job)).isNotNull();
            assertThat(scalar(worker, Timestamp.class, "SELECT sent_at FROM email_job WHERE id = ?", job)).isNull();

            var token = UUID.randomUUID();
            update(worker, "UPDATE email_job SET state = 'LEASED', lease_token = ?, lease_until = now() + interval '1 minute', "
                    + "attempts = 1 WHERE id = ?", token, job);
            update(worker, "UPDATE email_job SET lease_until = now() - interval '1 hour' WHERE id = ?", job);
            update(worker, "UPDATE email_job SET state = 'SENT', lease_token = NULL, lease_until = NULL, sent_at = now() "
                    + "WHERE id = ?", job);
            assertThat(scalar(worker, String.class, "SELECT state FROM email_job WHERE id = ?", job)).isEqualTo("SENT");
            assertThat(scalar(worker, Timestamp.class, "SELECT sent_at FROM email_job WHERE id = ?", job)).isNotNull();
            denied(() -> update(worker, "UPDATE email_job SET delivery_id = ? WHERE id = ?", inApp, job));
            denied(() -> update(worker, "UPDATE email_job SET identity_id = ? WHERE id = ?", f.otherIdentity, job));
            denied(() -> update(app, "UPDATE email_job SET state = 'PENDING' WHERE id = ?", job));

            var duplicateJob = failure(() -> update(worker,
                    "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), email, f.company, f.event,
                    f.requesterIdentity, f.requesterMembership));
            assertState(duplicateJob, "23505");
            assertConstraint(duplicateJob, "uq_email_job_delivery");

            var jobForInApp = failure(() -> update(worker,
                    "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), inApp, f.company, f.event,
                    f.requesterIdentity, f.requesterMembership));
            assertState(jobForInApp, "23503");
            assertConstraint(jobForInApp, "fk_email_job_delivery");

            var skipped = UUID.randomUUID();
            insertDelivery(worker, skipped, skippedFixture.company, skippedFixture.event,
                    skippedFixture.requesterIdentity, skippedFixture.requesterMembership, "IN_APP", "SKIPPED",
                    "recipient_inactive");
            var childOfSkipped = failure(() -> insertInApp(worker, UUID.randomUUID(), skipped, skippedFixture.company,
                    skippedFixture.event, skippedFixture.requesterIdentity, skippedFixture.requesterMembership));
            assertState(childOfSkipped, "23503");
            assertConstraint(childOfSkipped, "fk_in_app_notification_delivery");
            denied(() -> update(worker, "UPDATE in_app_notification SET read_at = NULL WHERE id = ?", notification));

            var addressDelivery = UUID.randomUUID();
            insertDelivery(worker, addressDelivery, skippedFixture.company, skippedFixture.event,
                    skippedFixture.requesterIdentity, skippedFixture.requesterMembership, "EMAIL", "MATERIALIZED", null);
            var addressOverride = failure(() -> update(worker,
                    "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id, "
                            + "recipient_address) VALUES (?, ?, ?, ?, ?, ?, 'attacker@example.invalid')",
                    UUID.randomUUID(), addressDelivery, skippedFixture.company, skippedFixture.event,
                    skippedFixture.requesterIdentity, skippedFixture.requesterMembership));
            assertState(addressOverride, "428C9");
        }
    }

    @Test
    void dispatchAndEmailStateShapesAllowExpiryButRejectInvalidWrites() throws Exception {
        var f = fixture();
        try (var worker = workerConnection(); var app = appConnection()) {
            var token = UUID.randomUUID();
            update(worker, "UPDATE outbox_dispatch SET state = 'LEASED', lease_token = ?, "
                    + "lease_until = now() + interval '1 minute', attempts = 1 WHERE event_id = ?", token, f.event);
            assertThat(scalar(worker, String.class, "SELECT state FROM outbox_dispatch WHERE event_id = ?", f.event))
                    .isEqualTo("LEASED");
            update(worker, "UPDATE outbox_dispatch SET lease_until = now() - interval '1 hour' WHERE event_id = ?", f.event);
            update(worker, "UPDATE outbox_dispatch SET state = 'PUBLISHED', lease_token = NULL, lease_until = NULL, "
                    + "published_at = now() WHERE event_id = ?", f.event);
            assertThat(scalar(worker, String.class, "SELECT state FROM outbox_dispatch WHERE event_id = ?", f.event))
                    .isEqualTo("PUBLISHED");

            var noLease = fixture();
            var dispatchNoLease = failure(() -> update(worker,
                    "UPDATE outbox_dispatch SET state = 'LEASED' WHERE event_id = ?", noLease.event));
            assertState(dispatchNoLease, "23514");
            assertConstraint(dispatchNoLease, "ck_outbox_dispatch_state_shape");

            var pendingLease = fixture();
            var dispatchPendingLease = failure(() -> update(worker,
                    "UPDATE outbox_dispatch SET state = 'PENDING', lease_token = ?, lease_until = now() WHERE event_id = ?",
                    UUID.randomUUID(), pendingLease.event));
            assertState(dispatchPendingLease, "23514");
            assertConstraint(dispatchPendingLease, "ck_outbox_dispatch_state_shape");

            var noPublished = fixture();
            var dispatchNoPublished = failure(() -> update(worker,
                    "UPDATE outbox_dispatch SET state = 'PUBLISHED' WHERE event_id = ?", noPublished.event));
            assertState(dispatchNoPublished, "23514");
            assertConstraint(dispatchNoPublished, "ck_outbox_dispatch_state_shape");

            var quarantine = fixture();
            var dispatchNoError = failure(() -> update(worker,
                    "UPDATE outbox_dispatch SET state = 'QUARANTINED' WHERE event_id = ?", quarantine.event));
            assertState(dispatchNoError, "23514");
            assertConstraint(dispatchNoError, "ck_outbox_dispatch_state_shape");

            var attempts = fixture();
            var negativeAttempts = failure(() -> update(worker,
                    "UPDATE outbox_dispatch SET attempts = -1 WHERE event_id = ?", attempts.event));
            assertState(negativeAttempts, "23514");
            assertConstraint(negativeAttempts, "ck_outbox_dispatch_attempts");

            var errorCode = fixture();
            var badErrorCode = failure(() -> update(worker,
                    "UPDATE outbox_dispatch SET error_code = 'Bad Code' WHERE event_id = ?", errorCode.event));
            assertState(badErrorCode, "23514");
            assertConstraint(badErrorCode, "ck_outbox_dispatch_error_code");

            var linkage = fixture();
            denied(() -> update(worker, "UPDATE outbox_dispatch SET event_id = ? WHERE event_id = ?",
                    UUID.randomUUID(), linkage.event));
            denied(() -> update(worker, "UPDATE outbox_dispatch SET company_id = company_id WHERE event_id = ?",
                    linkage.event));
            denied(() -> update(worker, "DELETE FROM outbox_dispatch WHERE event_id = ?", linkage.event));
            denied(() -> update(app, "UPDATE outbox_dispatch SET attempts = 0 WHERE event_id = ?", linkage.event));

            var emailDelivery = UUID.randomUUID();
            insertDelivery(worker, emailDelivery, f.company, f.event, f.requesterIdentity, f.requesterMembership,
                    "EMAIL", "MATERIALIZED", null);
            var job = UUID.randomUUID();
            update(worker, "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", job, emailDelivery, f.company, f.event, f.requesterIdentity,
                    f.requesterMembership);
            var jobNoLease = failure(() -> update(worker,
                    "UPDATE email_job SET state = 'LEASED' WHERE id = ?", job));
            assertState(jobNoLease, "23514");
            assertConstraint(jobNoLease, "ck_email_job_state_shape");
            var jobSentNoTime = failure(() -> update(worker,
                    "UPDATE email_job SET state = 'SENT' WHERE id = ?", job));
            assertState(jobSentNoTime, "23514");
            assertConstraint(jobSentNoTime, "ck_email_job_state_shape");
            var jobSentWithError = failure(() -> update(worker,
                    "UPDATE email_job SET state = 'SENT', sent_at = now(), error_code = 'smtp_failed' WHERE id = ?", job));
            assertState(jobSentWithError, "23514");
            assertConstraint(jobSentWithError, "ck_email_job_state_shape");
            var jobNoQuarantineError = failure(() -> update(worker,
                    "UPDATE email_job SET state = 'QUARANTINED' WHERE id = ?", job));
            assertState(jobNoQuarantineError, "23514");
            assertConstraint(jobNoQuarantineError, "ck_email_job_state_shape");
            var jobNoSkipError = failure(() -> update(worker,
                    "UPDATE email_job SET state = 'SKIPPED' WHERE id = ?", job));
            assertState(jobNoSkipError, "23514");
            assertConstraint(jobNoSkipError, "ck_email_job_state_shape");
            update(worker, "UPDATE email_job SET state = 'SKIPPED', error_code = 'recipient_inactive' WHERE id = ?", job);
            assertThat(scalar(worker, String.class, "SELECT state FROM email_job WHERE id = ?", job)).isEqualTo("SKIPPED");
            denied(() -> update(worker, "UPDATE email_job SET delivery_id = delivery_id WHERE id = ?", job));
            denied(() -> update(app, "UPDATE email_job SET state = 'PENDING' WHERE id = ?", job));

            var second = fixture();
            var secondDelivery = UUID.randomUUID();
            insertDelivery(worker, secondDelivery, second.company, second.event, second.requesterIdentity,
                    second.requesterMembership, "EMAIL", "MATERIALIZED", null);
            var secondJob = UUID.randomUUID();
            update(worker, "INSERT INTO email_job (id, delivery_id, company_id, event_id, identity_id, membership_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?)", secondJob, secondDelivery, second.company, second.event,
                    second.requesterIdentity, second.requesterMembership);
            var negative = failure(() -> update(worker, "UPDATE email_job SET attempts = -1 WHERE id = ?", secondJob));
            assertState(negative, "23514");
            assertConstraint(negative, "ck_email_job_attempts");
            var badCode = failure(() -> update(worker,
                    "UPDATE email_job SET error_code = 'SMTP Failure' WHERE id = ?", secondJob));
            assertState(badCode, "23514");
            assertConstraint(badCode, "ck_email_job_error_code");
            update(worker, "UPDATE email_job SET state = 'LEASED', lease_token = ?, lease_until = now() + interval '1 minute' "
                    + "WHERE id = ?", UUID.randomUUID(), secondJob);
            update(worker, "UPDATE email_job SET lease_until = now() - interval '1 hour' WHERE id = ?", secondJob);
            assertThat(scalar(worker, String.class, "SELECT state FROM email_job WHERE id = ?", secondJob)).isEqualTo("LEASED");
        }
    }

    @Test
    void diagnosticsAreBoundedAndScoped() throws Exception {
        var f = fixture();
        var other = fixture();
        try (var worker = workerConnection(); var app = appConnection()) {
            var hash = "a".repeat(64);
            var scopedHash = "b".repeat(64);
            update(worker, "INSERT INTO notification_diagnostic (payload_hash, channel, error_code) "
                    + "VALUES (?, 'EMAIL', 'smtp_timeout')", hash);
            update(worker, "INSERT INTO notification_diagnostic (payload_hash, company_id, event_id, channel, error_code) "
                    + "VALUES (?, ?, ?, 'IN_APP', 'payload_invalid')", scopedHash, f.company, f.event);
            assertThat(scalar(worker, Integer.class, "SELECT attempts FROM notification_diagnostic WHERE payload_hash = ?",
                    hash)).isEqualTo(1);
            update(worker, "UPDATE notification_diagnostic SET attempts = attempts + 1, last_seen_at = now() "
                    + "WHERE payload_hash = ?", hash);
            assertThat(scalar(worker, Integer.class, "SELECT attempts FROM notification_diagnostic WHERE payload_hash = ?",
                    hash)).isEqualTo(2);
            denied(() -> update(worker, "UPDATE notification_diagnostic SET error_code = 'changed' WHERE payload_hash = ?",
                    hash));
            denied(() -> scalar(app, Integer.class, "SELECT count(*) FROM notification_diagnostic"));

            var scope = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, company_id, channel, error_code) "
                            + "VALUES (?, ?, 'EMAIL', 'bad_scope')", "c".repeat(64), f.company));
            assertState(scope, "23514");
            assertConstraint(scope, "ck_notification_diagnostic_scope");

            var tenant = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, company_id, event_id, channel, error_code) "
                            + "VALUES (?, ?, ?, 'EMAIL', 'wrong_tenant')", "d".repeat(64), f.company, other.event));
            assertState(tenant, "23503");
            assertConstraint(tenant, "fk_notification_diagnostic_event");

            var badHash = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, channel, error_code) VALUES ('not-a-hash', 'EMAIL', 'x')"));
            assertState(badHash, "23514");
            assertConstraint(badHash, "ck_notification_diagnostic_hash");

            var badChannel = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, channel, error_code) VALUES (?, 'SMS', 'x')",
                    "e".repeat(64)));
            assertState(badChannel, "23514");
            assertConstraint(badChannel, "ck_notification_diagnostic_channel");

            var badCode = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, channel, error_code) VALUES (?, 'EMAIL', 'Bad Code')",
                    "f".repeat(64)));
            assertState(badCode, "23514");
            assertConstraint(badCode, "ck_notification_diagnostic_error");

            var zeroAttempts = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, channel, error_code, attempts) VALUES (?, 'EMAIL', 'x', 0)",
                    "1".repeat(64)));
            assertState(zeroAttempts, "23514");
            assertConstraint(zeroAttempts, "ck_notification_diagnostic_attempts");

            var badSeen = failure(() -> update(worker,
                    "INSERT INTO notification_diagnostic (payload_hash, channel, error_code, first_seen_at, last_seen_at) "
                            + "VALUES (?, 'EMAIL', 'x', now(), now() - interval '1 minute')", "2".repeat(64)));
            assertState(badSeen, "23514");
            assertConstraint(badSeen, "ck_notification_diagnostic_seen");
        }
    }

    @Test
    void rollbackLeavesNoDeliveryRowsOrProcessedMarker() throws Exception {
        var f = fixture();
        var delivery = UUID.randomUUID();
        try (var worker = workerConnection()) {
            worker.setAutoCommit(false);
            try {
                update(worker, "INSERT INTO notification_inbox (company_id, event_id) VALUES (?, ?)", f.company, f.event);
                insertDelivery(worker, delivery, f.company, f.event, f.requesterIdentity, f.requesterMembership,
                        "IN_APP", "MATERIALIZED", null);
                insertInApp(worker, UUID.randomUUID(), delivery, f.company, f.event, f.requesterIdentity,
                        f.requesterMembership);
                assertThat(scalar(worker, Integer.class,
                        "SELECT count(*) FROM notification_inbox WHERE company_id = ? AND event_id = ?",
                        f.company, f.event)).isEqualTo(1);
                assertThat(scalar(worker, Integer.class,
                        "SELECT count(*) FROM notification_delivery WHERE company_id = ? AND event_id = ?",
                        f.company, f.event)).isEqualTo(1);
                assertThat(scalar(worker, Integer.class,
                        "SELECT count(*) FROM in_app_notification WHERE delivery_id = ?", delivery)).isEqualTo(1);
            } finally {
                worker.rollback();
            }
            assertThat(scalar(worker, Integer.class,
                    "SELECT count(*) FROM notification_inbox WHERE company_id = ? AND event_id = ?",
                    f.company, f.event)).isZero();
            assertThat(scalar(worker, Integer.class,
                    "SELECT count(*) FROM notification_delivery WHERE company_id = ? AND event_id = ?",
                    f.company, f.event)).isZero();
            assertThat(scalar(worker, Integer.class,
                    "SELECT count(*) FROM in_app_notification WHERE delivery_id = ?", delivery)).isZero();
        }
    }

    @Test
    void companyLockFunctionBlocksTheMembershipAdminBarrier() throws Exception {
        var f = fixture();
        try (var worker = workerConnection(); var app = appConnection()) {
            denied(() -> update(worker, "UPDATE company SET name = name WHERE id = ?", f.company));
            denied(() -> scalar(worker, UUID.class, "SELECT id FROM company WHERE id = ? FOR UPDATE", f.company));

            var workerPid = scalar(worker, Integer.class, "SELECT pg_backend_pid()");
            var appPid = scalar(app, Integer.class, "SELECT pg_backend_pid()");
            assertThat(appPid).as("admin and worker must run on distinct backends").isNotEqualTo(workerPid);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<UUID> barrier = null;
            worker.setAutoCommit(false);
            app.setAutoCommit(false);
            try {
                execute(worker, "SELECT public.lock_notification_company(?)", f.company);
                try (var statement = app.createStatement()) {
                    statement.execute("SET lock_timeout = '20s'");
                }
                var submitted = executor.submit(() -> {
                    try (var statement = app.prepareStatement(
                            "UPDATE company SET name = name WHERE id = ? RETURNING id")) {
                        statement.setObject(1, f.company);
                        try (var rows = statement.executeQuery()) {
                            rows.next();
                            return rows.getObject(1, UUID.class);
                        }
                    }
                });
                barrier = submitted;

                assertBlockedBehindWorkerBarrier(appPid, workerPid);
                assertThat(submitted.isDone())
                        .as("membership admin update completed before the company SHARE barrier was released")
                        .isFalse();
                worker.commit();
                assertThat(barrier.get(10, TimeUnit.SECONDS)).isEqualTo(f.company);
                update(app, "UPDATE membership SET active = false WHERE id = ?", f.requesterMembership);
                app.commit();
            } finally {
                try { worker.rollback(); } catch (SQLException ignored) { }
                if (barrier != null && !barrier.isDone()) {
                    try { barrier.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { }
                    barrier.cancel(true);
                }
                try { app.rollback(); } catch (SQLException ignored) { }
                executor.shutdownNow();
                executor.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
        assertThat(migrator.queryForObject("SELECT active FROM membership WHERE id = ?", Boolean.class,
                f.requesterMembership)).isFalse();
    }

    /**
     * PostgreSQL-level proof that the admin backend is blocked at the worker's company SHARE barrier:
     * the admin backend shows wait_event_type 'Lock' while running the submitted company update, and the
     * worker backend is among its direct blockers. The polling deadline stays below the admin lock_timeout
     * of 20s, so a merely slow-but-unblocked update fails instead of passing on timing alone.
     */
    private static void assertBlockedBehindWorkerBarrier(int blockedPid, int blockerPid) throws Exception {
        var timeout = TimeUnit.SECONDS.toNanos(10);
        var start = System.nanoTime();
        while (System.nanoTime() - start < timeout) {
            if (blockedBehindWorkerBarrier(blockedPid, blockerPid)) {
                return;
            }
            // Bounded poll interval; the pass condition is the observed database state, never the sleep.
            Thread.sleep(50);
        }
        fail(("admin backend %d was never observed waiting on the company barrier held by worker backend %d; "
                + "last pg_stat_activity row: %s").formatted(blockedPid, blockerPid, activity(blockedPid)));
    }

    private static boolean blockedBehindWorkerBarrier(int blockedPid, int blockerPid) {
        var rows = owner.queryForList("""
                SELECT 1 FROM pg_stat_activity a
                WHERE a.pid = ?
                  AND a.state = 'active'
                  AND a.wait_event_type = 'Lock'
                  AND ? = ANY (pg_blocking_pids(a.pid))
                  AND a.query LIKE 'UPDATE company%'
                """, blockedPid, blockerPid);
        return !rows.isEmpty();
    }

    private static String activity(int pid) {
        var rows = owner.queryForList("SELECT state, wait_event_type, wait_event, query, "
                + "pg_blocking_pids(pid) AS blockers FROM pg_stat_activity WHERE pid = ?", pid);
        return rows.isEmpty() ? "no pg_stat_activity row for pid " + pid : rows.get(0).toString();
    }

    @Test
    void securityDefinerFunctionsAreMigratorOwnedAndNarrowlyExecutable() {
        var lock = migrator.queryForMap("""
                SELECT p.prosecdef, pg_catalog.pg_get_userbyid(p.proowner) AS owner,
                       coalesce(array_to_string(p.proconfig, ','), '') AS config,
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                               WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE') AS public_execute
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'lock_notification_company'
                """);
        assertSecurityDefinerContract(lock);

        var trigger = migrator.queryForMap("""
                SELECT p.prosecdef, pg_catalog.pg_get_userbyid(p.proowner) AS owner,
                       coalesce(array_to_string(p.proconfig, ','), '') AS config,
                       EXISTS (SELECT 1 FROM aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a
                               WHERE a.grantee = 0 AND a.privilege_type = 'EXECUTE') AS public_execute
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'public' AND p.proname = 'initialize_outbox_dispatch'
                """);
        assertSecurityDefinerContract(trigger);
    }

    private static void assertSecurityDefinerContract(java.util.Map<String, Object> function) {
        assertThat(function.get("prosecdef")).isEqualTo(true);
        assertThat(function.get("owner")).isEqualTo(MIGRATOR_USER);
        assertThat((String) function.get("config")).contains("search_path=pg_catalog, pg_temp");
        assertThat(function.get("public_execute")).isEqualTo(false);
    }

    private static Fixture fixture() {
        var now = Instant.now();
        var company = UUID.randomUUID();
        var department = UUID.randomUUID();
        var requester = UUID.randomUUID();
        var requesterMembership = UUID.randomUUID();
        var other = UUID.randomUUID();
        var otherMembership = UUID.randomUUID();
        var request = UUID.randomUUID();
        var event = UUID.randomUUID();
        var budget = UUID.randomUUID();
        migrator.update("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, ?)",
                company, "p4-" + company, "P4 fixture", Timestamp.from(now));
        migrator.update("INSERT INTO department (id, company_id, name) VALUES (?, ?, 'Engineering')",
                department, company);
        migrator.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                requester, requester.toString());
        migrator.update("INSERT INTO app_identity (id, issuer, subject) VALUES (?, 'https://fixture.invalid', ?)",
                other, other.toString());
        migrator.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, true)", requesterMembership, company, requester, department);
        migrator.update("INSERT INTO membership (id, company_id, identity_id, department_id, active) "
                + "VALUES (?, ?, ?, ?, true)", otherMembership, company, other, department);
        migrator.update("INSERT INTO monthly_budget (id, company_id, department_id, budget_month, limit_amount) "
                + "VALUES (?, ?, ?, '2026-10-01', 1000.00)", budget, company, department);
        insertRequest(migrator, company, department, requester, requesterMembership, request);
        insertEvent(migrator, company, request, event, now);
        insertRecipient(migrator, company, event, requester, requesterMembership);
        return new Fixture(company, department, requester, requesterMembership, other, otherMembership, request, event,
                budget);
    }

    private static void insertRequest(JdbcTemplate jdbc, UUID company, UUID department, UUID identity, UUID membership,
                                      UUID request) {
        jdbc.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id,
                department_id, title, justification, amount, created_at, updated_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'P4 fixture request', 'Schema fixture only', 10.00, now(), now(), ?)
                """, request, company, identity, membership, department, UUID.randomUUID());
    }

    private static void insertEvent(JdbcTemplate jdbc, UUID company, UUID request, UUID event, Instant occurredAt) {
        var correlation = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, correlation_id, "
                        + "occurred_at, envelope) VALUES (?, ?, ?, 'request.submitted', 1, ?, ?, CAST(? AS jsonb))",
                event, company, request, correlation, Timestamp.from(occurredAt),
                envelope(company, request, event, correlation));
    }

    private static void insertRecipient(JdbcTemplate jdbc, UUID company, UUID event, UUID identity, UUID membership) {
        jdbc.update("INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) VALUES (?, ?, ?, ?)",
                company, event, identity, membership);
    }

    private static void insertEvent(Connection connection, UUID company, UUID request, UUID event, Instant occurredAt)
            throws SQLException {
        var correlation = UUID.randomUUID();
        update(connection, "INSERT INTO outbox_event (id, company_id, request_id, event_type, schema_version, "
                        + "correlation_id, occurred_at, envelope) VALUES (?, ?, ?, 'request.submitted', 1, ?, ?, CAST(? AS jsonb))",
                event, company, request, correlation, Timestamp.from(occurredAt),
                envelope(company, request, event, correlation));
    }

    private static void insertRecipient(Connection connection, UUID company, UUID event, UUID identity, UUID membership)
            throws SQLException {
        update(connection, "INSERT INTO outbox_recipient (company_id, event_id, identity_id, membership_id) "
                + "VALUES (?, ?, ?, ?)", company, event, identity, membership);
    }

    private static String envelope(UUID company, UUID request, UUID event, UUID correlation) {
        return """
                {"eventId":"%s","companyId":"%s","requestId":"%s","type":"request.submitted",
                 "correlationId":"%s","schemaVersion":1}
                """.formatted(event, company, request, correlation);
    }

    private static void insertDelivery(Connection connection, UUID id, UUID company, UUID event, UUID identity,
                                       UUID membership, String channel, String outcome, String reasonCode)
            throws SQLException {
        update(connection, "INSERT INTO notification_delivery (id, company_id, event_id, identity_id, membership_id, "
                        + "channel, outcome, reason_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, company, event, identity, membership, channel, outcome, reasonCode);
    }

    private static void insertInApp(Connection connection, UUID id, UUID delivery, UUID company, UUID event,
                                    UUID identity, UUID membership) throws SQLException {
        update(connection, "INSERT INTO in_app_notification (id, delivery_id, company_id, event_id, identity_id, "
                        + "membership_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, delivery, company, event, identity, membership);
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(url, APP_USER, TestDatabase.appPassword());
    }

    private static Connection workerConnection() throws SQLException {
        return DriverManager.getConnection(url, WORKER_USER, TestDatabase.workerPassword());
    }

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.execute();
        }
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T scalar(Connection connection, Class<T> type, String sql, Object... parameters)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).as("query returned no row").isTrue();
                var value = rows.getObject(1);
                if (value == null) return null;
                if (type == Integer.class && value instanceof Number number) return (T) Integer.valueOf(number.intValue());
                if (type == Long.class && value instanceof Number number) return (T) Long.valueOf(number.longValue());
                return (T) value;
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (var index = 0; index < parameters.length; index++) {
            var value = parameters[index];
            if (value == null) statement.setNull(index + 1, Types.OTHER);
            else statement.setObject(index + 1, value);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    private static SQLException failure(SqlAction action) {
        try {
            action.run();
            fail("Expected SQLException");
            return null;
        } catch (SQLException error) {
            return error;
        }
    }

    private static void denied(SqlAction action) {
        assertState(failure(action), "42501");
    }

    private static void assertState(SQLException error, String state) {
        assertThat(error.getSQLState()).as(error.getMessage()).isEqualTo(state);
    }

    private static void assertConstraint(SQLException error, String constraint) {
        assertThat((Object) error).isInstanceOf(PSQLException.class);
        var server = ((PSQLException) error).getServerErrorMessage();
        assertThat(server).as(error.getMessage()).isNotNull();
        assertThat(server.getConstraint()).as(error.getMessage()).isEqualTo(constraint);
    }
}