package com.procureflow.app.requests;

import com.procureflow.app.identity.Actor;
import com.procureflow.app.identity.DemoData;
import com.procureflow.app.identity.Role;
import com.procureflow.app.shared.ApiException;
import com.procureflow.app.support.CountingDataSourceConfiguration;
import com.procureflow.app.support.QueryFixtures;
import com.procureflow.app.support.TestDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.procureflow.app.identity.DemoData.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P8.2 pagination evidence for the request list: exactly one statement per page (count and items
 * from the same PostgreSQL snapshot), preserved page/total/ordering semantics, and consistency
 * while rows are inserted concurrently.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@Import(CountingDataSourceConfiguration.class)
@SpringBootTest
class RequestPaginationQueryCountIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired RequestRepository requests;
    @Autowired DemoData demo;
    JdbcTemplate owner;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
    }

    @BeforeEach void reset() {
        owner = new JdbcTemplate(new DriverManagerDataSource(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()));
        owner.execute("""
                TRUNCATE notification_diagnostic, email_job, in_app_notification, notification_delivery, notification_inbox,
                    outbox_dispatch, outbox_recipient, outbox_event, request_history, api_command, budget_charge,
                    approval_step, audit_entry, manager_department, membership_role, monthly_budget, membership,
                    department, app_identity, company CASCADE
                """);
        demo.run(null);
        for (var index = 0; index < 12; index++) {
            QueryFixtures.seedDraftRequest(owner, id("a"), id("a:employee:identity"), id("a:employee:membership"),
                    id("a:engineering"), BASE.plusSeconds(index));
        }
        for (var index = 0; index < 4; index++) seedSubmittedRequest(BASE.plusSeconds(100 + index));
    }

    @Test
    void oneStatementPerPagePreservesPageTotalAndOrdering() {
        var counting = CountingDataSourceConfiguration.counting();
        var employee = employee();

        counting.reset();
        var first = requests.page(employee, "mine", null, 0, 5);
        System.out.println("[QUERY-COUNT] RequestRepository.page(): " + counting.count() + " statement(s) per call");
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(1);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(5);
        assertThat(first.total()).isEqualTo(16);
        assertThat(first.items()).hasSize(5);
        assertThat(first.items().getFirst().createdAt()).isEqualTo(BASE.plusSeconds(103));

        var last = requests.page(employee, "mine", null, 3, 5);
        assertThat(last.total()).isEqualTo(16);
        assertThat(last.items()).hasSize(1);
        assertThat(last.items().getLast().createdAt()).isEqualTo(BASE);

        var empty = requests.page(employee, "mine", null, 4, 5);
        assertThat(empty.total()).isEqualTo(16);
        assertThat(empty.items()).isEmpty();
        assertThat(counting.count()).isEqualTo(3);

        var manager = new Actor(id("a:manager:identity"), id("a:manager:membership"), id("a"), "Larkvane",
                id("a:engineering"), "engineering", 0, Set.of(Role.DEPARTMENT_MANAGER), Set.of(id("a:engineering")));
        counting.reset();
        var inbox = requests.page(manager, "inbox", null, 0, 5);
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(1);
        assertThat(inbox.total()).isEqualTo(4);
        assertThat(inbox.items()).hasSize(4);
        assertThat(inbox.items().getFirst().createdAt()).isEqualTo(BASE.plusSeconds(103));

        counting.reset();
        var filtered = requests.page(manager, "inbox", RequestStatus.AWAITING_DEPARTMENT_APPROVAL, 0, 10);
        assertThat(counting.count()).isEqualTo(1);
        assertThat(filtered.total()).isEqualTo(4);

        assertThatThrownBy(() -> requests.page(employee, "mine", null, 1_000_001, 20))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo("invalid_page"));
        assertThatThrownBy(() -> requests.page(employee, "mine", null, 0, 101))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo("invalid_page"));
    }

    @Test
    void totalAndItemsStayConsistentWhileRowsAreInsertedConcurrently() throws Exception {
        var employee = employee();
        var inserting = Executors.newSingleThreadExecutor();
        try {
            var inserts = inserting.submit(() -> {
                for (var index = 0; index < 60; index++) {
                    QueryFixtures.seedDraftRequest(owner, id("a"), id("a:employee:identity"), id("a:employee:membership"),
                            id("a:engineering"), BASE.plusSeconds(3600 + index));
                }
                return null;
            });
            for (var iteration = 0; iteration < 120; iteration++) {
                var page = requests.page(employee, "mine", null, 0, 7);
                assertThat(page.items().size()).as("page " + iteration + " total=" + page.total())
                        .isEqualTo((int) Math.min(7, page.total()));
                for (var index = 1; index < page.items().size(); index++) {
                    assertThat(page.items().get(index - 1).createdAt()).isAfterOrEqualTo(page.items().get(index).createdAt());
                }
            }
            inserts.get(30, TimeUnit.SECONDS);
        } finally {
            inserting.shutdownNow();
        }
    }

    private Actor employee() {
        return new Actor(id("a:employee:identity"), id("a:employee:membership"), id("a"), "Larkvane",
                id("a:engineering"), "engineering", 0, Set.of(Role.EMPLOYEE), Set.of());
    }

    private void seedSubmittedRequest(Instant createdAt) {
        var request = UUID.randomUUID();
        owner.update("""
                INSERT INTO purchase_request (id, company_id, requester_identity_id, requester_membership_id, department_id,
                    title, justification, amount, status, created_at, updated_at, submitted_at, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'Submitted fixture', 'Deterministic fixture', 10.00, 'AWAITING_DEPARTMENT_APPROVAL', ?, ?, ?, ?)
                """, request, id("a"), id("a:employee:identity"), id("a:employee:membership"), id("a:engineering"),
                Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt), UUID.randomUUID());
    }
}
