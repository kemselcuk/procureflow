package com.procureflow.app.notifications;

import com.procureflow.app.identity.Actor;
import com.procureflow.app.identity.DemoData;
import com.procureflow.app.identity.Role;
import com.procureflow.app.shared.ApiException;
import com.procureflow.app.support.CountingDataSourceConfiguration;
import com.procureflow.app.support.QueryFixtures;
import com.procureflow.app.support.TestDatabase;
import java.time.Instant;
import java.util.Set;
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
 * P8.2 pagination evidence for the own-notification list: one statement per page, same page/total
 * semantics as the previous two-statement implementation, and the shared page bound.
 */
@Testcontainers
@ActiveProfiles("local-demo")
@Import(CountingDataSourceConfiguration.class)
@SpringBootTest
class NotificationPaginationQueryCountIT {
    @Container static final PostgreSQLContainer DATABASE = TestDatabase.create();
    static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired NotificationRepository notifications;
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
        for (var index = 0; index < 7; index++) {
            QueryFixtures.seedNotification(owner, id("a"), id("a:employee:identity"), id("a:employee:membership"),
                    id("a:engineering"), BASE.plusSeconds(index));
        }
    }

    @Test
    void oneStatementPerPagePreservesPageTotalAndOrdering() {
        var counting = CountingDataSourceConfiguration.counting();
        var employee = employee();

        counting.reset();
        var first = notifications.page(employee, 0, 5);
        System.out.println("[QUERY-COUNT] NotificationRepository.page(): " + counting.count() + " statement(s) per call");
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(1);
        assertThat(first.page()).isZero();
        assertThat(first.size()).isEqualTo(5);
        assertThat(first.total()).isEqualTo(7);
        assertThat(first.items()).hasSize(5);
        assertThat(first.items().getFirst().createdAt()).isEqualTo(BASE.plusSeconds(6));

        var last = notifications.page(employee, 1, 5);
        assertThat(last.total()).isEqualTo(7);
        assertThat(last.items()).hasSize(2);
        assertThat(last.items().getLast().createdAt()).isEqualTo(BASE);

        var empty = notifications.page(employee, 2, 5);
        assertThat(empty.total()).isEqualTo(7);
        assertThat(empty.items()).isEmpty();
        assertThat(counting.count()).isEqualTo(3);

        var other = new Actor(id("a:finance:identity"), id("a:finance:membership"), id("a"), "Larkvane",
                id("a:engineering"), "engineering", 0, Set.of(Role.FINANCE_APPROVER), Set.of());
        counting.reset();
        var none = notifications.page(other, 0, 20);
        assertThat(counting.count()).as(counting.statements()::toString).isEqualTo(1);
        assertThat(none.total()).isZero();
        assertThat(none.items()).isEmpty();

        assertThatThrownBy(() -> notifications.page(employee, 1_000_001, 20))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo("invalid_page"));
        assertThatThrownBy(() -> notifications.page(employee, 0, 0))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo("invalid_page"));
    }

    private Actor employee() {
        return new Actor(id("a:employee:identity"), id("a:employee:membership"), id("a"), "Larkvane",
                id("a:engineering"), "engineering", 0, Set.of(Role.EMPLOYEE), Set.of());
    }
}
