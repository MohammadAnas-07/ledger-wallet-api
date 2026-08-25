package com.anas.ledgerwallet;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for integration tests: boots the full application against a real
 * PostgreSQL instance.
 *
 * <p>A real database, not H2. From Phase 4 onward these tests have to exercise
 * optimistic locking and MVCC behaviour, which an in-memory database does not
 * reproduce — a green test there would prove nothing about {@code @Version}
 * (rules.md 3.2).
 *
 * <p>The container is a singleton started once for the whole test JVM rather than
 * per class, so adding integration test classes costs startup time only once.
 * Testcontainers stops it when the JVM exits.
 *
 * <p>Requires a running Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger_wallet_test")
                    .withUsername("test")
                    .withPassword("test")
                    // The default is 60s, and first startup on a cold Docker Desktop has
                    // been observed at ~45s. Too close to the limit to leave alone: a
                    // timeout here would surface as an intermittent failure, which
                    // rules.md 3.2 says must never be written off as a flaky test.
                    .withStartupTimeout(Duration.ofMinutes(3));

    static {
        POSTGRES.start();
    }

    /**
     * A fixed test signing key, long enough to satisfy the HS256 minimum that
     * {@code JwtService} enforces at startup. Test-only and committed on purpose —
     * it signs nothing outside this suite, and the real key comes from the
     * environment with no fallback (rules.md 2.5).
     */
    private static final String TEST_JWT_SECRET =
            "integration-test-signing-key-not-used-anywhere-else";

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
        registry.add("JWT_SECRET", () -> TEST_JWT_SECRET);
        registry.add("JWT_EXPIRATION_MINUTES", () -> 15);
    }
}
