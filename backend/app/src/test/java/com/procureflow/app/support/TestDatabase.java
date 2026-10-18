package com.procureflow.app.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

public final class TestDatabase {
    private TestDatabase() {}
    private static final String APP_PASSWORD = UUID.randomUUID().toString();
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String WORKER_PASSWORD = UUID.randomUUID().toString();

    public static PostgreSQLContainer create() {
        var root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("infra/postgres/init-databases.sh"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Cannot find repository database initialization script");
        }
        return new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")))
                .withEnv("APP_DB_PASSWORD", APP_PASSWORD)
                .withEnv("MIGRATION_DB_PASSWORD", MIGRATION_PASSWORD)
                .withEnv("WORKER_DB_PASSWORD", WORKER_PASSWORD)
                .withEnv("KEYCLOAK_DB_PASSWORD", UUID.randomUUID().toString())
                .withCopyFileToContainer(MountableFile.forHostPath(root.resolve("infra/postgres/init-databases.sh")),
                        "/docker-entrypoint-initdb.d/10-procureflow.sh");
    }

    public static void properties(PostgreSQLContainer database, DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", database::getJdbcUrl);
        properties.add("spring.datasource.username", () -> "procureflow_app");
        properties.add("spring.datasource.password", () -> APP_PASSWORD);
        properties.add("spring.flyway.user", () -> "procureflow_migrator");
        properties.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    }

    public static String appPassword() { return APP_PASSWORD; }
    public static String migrationPassword() { return MIGRATION_PASSWORD; }
    public static String workerPassword() { return WORKER_PASSWORD; }
}
