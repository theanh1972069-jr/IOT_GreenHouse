package com.iot.greenhouse.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Runs integration tests against a disposable PostgreSQL container.
 *
 * <p>Previously the tests pointed at a locally installed {@code iot_greenhouse_test}
 * database, so they failed on any machine or CI runner where that database had not
 * already been created and seeded by hand.
 *
 * <p>The schema comes from {@code database/create_tables.sql}, the same file used to
 * provision a real database, wired onto the test classpath as
 * {@code db/create_tables.sql} by the {@code testResources} entry in
 * {@code pom.xml}. Production runs with {@code ddl-auto=none}, so applying the real
 * schema keeps the tests honest about the entities matching the actual tables.
 *
 * <p><strong>Singleton container.</strong> The usual {@code @Container} annotation
 * starts a container per test class and stops it afterwards, so with several test
 * classes the second class would find the first one's port already closed. Starting
 * it once per JVM behind a guard and shutting it down from a JVM hook avoids that.
 *
 * <p>Requires a running Docker daemon. Without Docker the tests are skipped rather
 * than failed, so {@code mvn test} still works on a machine that has none.
 */
public abstract class TestDatabase {

    private static final Object LOCK = new Object();
    private static PostgreSQLContainer<?> postgres;

    static {
        start();
    }

    private static void start() {
        synchronized (LOCK) {
            if (postgres != null) {
                return;
            }
            if (!DockerAvailable.isAvailable()) {
                return;
            }
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("iot_greenhouse_test")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
            postgres = container;
            Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        }
    }

    /** @return {@code true} when the shared container is running. */
    public static boolean isRunning() {
        return postgres != null && postgres.isRunning();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Only consulted once the context is actually built, which cannot happen for a
        // skipped class, so the null case is unreachable in practice.
        PostgreSQLContainer<?> container = postgres;
        registry.add("spring.datasource.url", () -> container.getJdbcUrl());
        registry.add("spring.datasource.username", () -> container.getUsername());
        registry.add("spring.datasource.password", () -> container.getPassword());
    }
}
