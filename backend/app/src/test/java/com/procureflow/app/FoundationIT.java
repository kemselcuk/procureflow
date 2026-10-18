package com.procureflow.app;

import com.procureflow.app.support.TestDatabase;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIT {
    @Container
    static final PostgreSQLContainer DATABASE = TestDatabase.create();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.properties(DATABASE, properties);
    }

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    @Test
    void migratesEmptyPostgresAndRejectsInvalidCompanyRecords() throws Exception {
        assertThat(Runtime.version().feature()).isEqualTo(21);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("5");
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var connection = DriverManager.getConnection(DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword());
             var insert = connection.prepareStatement("INSERT INTO company (id, slug, name, created_at) VALUES (?, ?, ?, now())")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, "foundation-company");
            insert.setString(3, "Foundation company");
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setObject(1, UUID.randomUUID());
            assertThatThrownBy(insert::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("23505"));
            insert.setString(2, "another-company");
            insert.setString(3, "   ");
            assertThatThrownBy(insert::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("23514"));
        }
    }

    @Test
    void servesRealOpenApiWithBothJacksonFamiliesAndHealthyDatabase() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var mapper = JsonMapper.builder().build();
            var docs = get(client, "/v3/api-docs");
            assertThat(docs.statusCode()).isEqualTo(200);
            var document = mapper.readTree(docs.body());
            assertThat(document.path("openapi").asText()).startsWith("3.");
            assertThat(document.path("paths").has("/api/system")).isTrue();
            assertThat(document.path("components").path("schemas").path("SystemInfo").path("properties").has("stage")).isTrue();
            var system = get(client, "/api/system");
            assertThat(system.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(system.body()).path("stage").asText()).isEqualTo("foundation");
            var readiness = get(client, "/actuator/health/readiness");
            assertThat(readiness.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(readiness.body()).path("status").asText()).isEqualTo("UP");
        }
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
