package com.aiinterview.interviewplatform.support;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for every test that needs a real PostgreSQL.
 *
 * <p>PostgreSQL, never H2. H2 silently accepts things PostgreSQL rejects, and
 * partial unique indexes, {@code num_nonnulls}, plpgsql triggers and
 * {@code FOR UPDATE SKIP LOCKED} are all load-bearing here — a test against
 * H2 would prove nothing about production.
 *
 * <p>The instance is resolved once per JVM by {@link TestDatabase}: a
 * Testcontainers container by default, or an external database when
 * {@code TEST_DATABASE_URL} is set.
 *
 * <p>Reaching a test body already proves two things: Flyway migrated an empty
 * database cleanly, and Hibernate's {@code ddl-auto=validate} then agreed that
 * all 18 entities match the migrated schema. A mismatch in any column name or
 * type fails the context before a single assertion runs.
 *
 * <p>Tagged {@code database} so the suite can be skipped where neither option
 * exists: {@code mvn test -Pno-docker}.
 */
@Tag("database")
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractDatabaseTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase database = TestDatabase.resolve();
        registry.add("spring.datasource.url", database::url);
        registry.add("spring.datasource.username", database::username);
        registry.add("spring.datasource.password", database::password);
    }
}
