package com.aiinterview.interviewplatform.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Resolves the PostgreSQL instance the database tests run against.
 *
 * <p>Testcontainers is the default and the intended CI path. It is not the
 * only path, because a container runtime is not always available: Docker
 * Desktop needs administrator rights to install, and on a locked-down
 * workstation that is a hard stop. Rather than leaving the invariant suite
 * unrunnable for a developer in that position, an already-running PostgreSQL
 * can be pointed at instead:
 *
 * <pre>
 *   set TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/interview_platform_test
 *   set TEST_DATABASE_USERNAME=postgres
 *   set TEST_DATABASE_PASSWORD=...
 * </pre>
 *
 * <p>The database is migrated from empty by Flyway either way, so both paths
 * prove the same thing. Never point this at a database holding real data:
 * the invariant suite writes freely.
 */
final class TestDatabase {

    private static final String URL_PROPERTY = "TEST_DATABASE_URL";
    private static final String USER_PROPERTY = "TEST_DATABASE_USERNAME";
    private static final String PASSWORD_PROPERTY = "TEST_DATABASE_PASSWORD";

    /** Matches the PostgreSQL major version Supabase runs in production. */
    private static final String IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;

    private final String url;
    private final String username;
    private final String password;

    private TestDatabase(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    static synchronized TestDatabase resolve() {
        String externalUrl = setting(URL_PROPERTY);
        if (externalUrl != null && !externalUrl.isBlank()) {
            return new TestDatabase(
                    externalUrl,
                    orDefault(setting(USER_PROPERTY), "postgres"),
                    orDefault(setting(PASSWORD_PROPERTY), "postgres"));
        }
        if (container == null) {
            container = new PostgreSQLContainer<>(IMAGE)
                    .withDatabaseName("interview_platform")
                    .withUsername("interview")
                    .withPassword("interview");
            container.start();
        }
        return new TestDatabase(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** System property wins over environment variable, so the build can override. */
    private static String setting(String name) {
        String fromSystem = System.getProperty(name);
        return fromSystem != null ? fromSystem : System.getenv(name);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    String url() {
        return url;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }
}
