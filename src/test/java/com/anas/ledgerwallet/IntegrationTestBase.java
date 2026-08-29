package com.anas.ledgerwallet;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for integration tests: boots the full application against a real
 * PostgreSQL instance and a real Kafka broker (see {@link TestInfrastructure}).
 *
 * <p>A real database, not H2. From Phase 4 onward these tests have to exercise
 * optimistic locking and MVCC behaviour, which an in-memory database does not
 * reproduce — a green test there would prove nothing about {@code @Version}
 * (rules.md 3.2).
 *
 * <p>Every subclass shares one application context, so they also share the singleton
 * beans in it. That is why the rate limits are lifted below.
 *
 * <p>Requires a running Docker daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    /**
     * High enough to be unreachable by a test run.
     *
     * <p>Every integration test registers and logs in, all of them from 127.0.0.1
     * against one shared filter bean, so the production allowance would be spent part
     * way through a suite and later classes would start seeing 429s that have nothing
     * to do with what they assert. The limiter itself is proven by
     * {@code AuthRateLimitFilterTest} and {@code AuthRateLimitIT}, which sets its own
     * low limits in its own context.
     */
    private static final int UNLIMITED_FOR_TESTS = 1_000_000;

    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        TestInfrastructure.register(registry);

        registry.add("app.rate-limit.login.capacity", () -> UNLIMITED_FOR_TESTS);
        registry.add("app.rate-limit.register.capacity", () -> UNLIMITED_FOR_TESTS);
    }
}
